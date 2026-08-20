package com.ninepointnine.desktopcast.renderer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import kotlin.math.max

/**
 * Decodes AirPlay mirror frames directly into the active opaque SurfaceView.
 *
 * The decoder surface is deliberately owned by the Activity window. Keeping a
 * second SurfaceTexture/EGL surface in between the codec and SurfaceView makes
 * the Qualcomm display path fall back to client composition and loses frames
 * when a floating window changes size.
 */
class VideoRenderer {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var displayHolder: SurfaceHolder? = null
    private var displaySurface: Surface? = null
    private var requestedBufferWidth = 0
    private var requestedBufferHeight = 0
    private var bufferGeometryReady = false
    private var bufferGeometryRequestPending = false
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    private var lastQueuedPtsUs = Long.MIN_VALUE
    private var pendingKeyframe: PendingFrame? = null
    private var decoderProfile: MirrorDecoderProfile? = null
    private var softwareFallbackActive = false
    private var inputRateLastPtsUs = Long.MIN_VALUE
    private val inputRateSamplesUs = LongArray(INPUT_RATE_SAMPLE_COUNT)
    private var inputRateSampleCount = 0
    @Volatile var observedInputFps = 0; private set

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var renderedFrameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var lowLatency = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    /**
     * Installs the exact capability snapshot used for the AirPlay handshake.
     * It remains available across sender and Activity transitions while codec
     * and stream state are reset independently.
     */
    internal fun configureDecoderProfile(profile: MirrorDecoderProfile) = synchronized(lock) {
        if (decoderProfile == profile) return@synchronized
        stopCodec()
        decoderProfile = profile
        Log.i(
            TAG,
            "Mirror decoder profile configured: panel=${profile.panelWidth}x${profile.panelHeight}" +
                "@${profile.panelRefreshHz}, advertisedMaxFPS=${profile.advertisedMaxFrameRate}, " +
                "h265=${profile.supportsH265}, codecs=${profile.codecSummary()}",
        )
    }

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w <= 0 || h <= 0) {
            resetSurfaceBufferGeometry()
            videoWidth = 0
            videoHeight = 0
            pendingKeyframe = null
            stopCodec()
            return@synchronized
        }
        val hadResolution = videoWidth > 0 && videoHeight > 0
        val changed = videoWidth != w || videoHeight != h
        videoWidth = w
        videoHeight = h
        if (changed) {
            // The next SPS/PPS + IDR packet belongs to the new stream shape.
            if (hadResolution) pendingKeyframe = null
            stopCodec()
            resetInputRate()
        }
        applySurfaceBufferGeometry()
        if (!hadResolution && bufferGeometryReady) drainPendingKeyframe()
    }

    /**
     * Binds the decoder to the current window surface. Android 9 exposes
     * setOutputSurface(), but a few vendor codec builds reject it while a
     * buffer is being drained; the fallback is a clean codec restart at the
     * next keyframe rather than leaving the old surface black.
     */
    fun setSurface(
        holder: SurfaceHolder,
        bufferWidth: Int = 0,
        bufferHeight: Int = 0,
    ) = synchronized(lock) {
        val surface = holder.surface
        if (!surface.isValid) {
            Log.w(TAG, "Ignoring invalid mirror surface")
            return@synchronized
        }
        val previousHolder = displayHolder
        val previous = displaySurface
        displayHolder = holder
        displaySurface = surface
        if (previousHolder !== holder || previous !== surface) {
            bufferGeometryRequestPending = videoWidth > 0 && videoHeight > 0
            if (bufferGeometryRequestPending) {
                // A new Surface has its own BufferQueue even when the coded
                // dimensions happen to match the previous Activity.
                requestedBufferWidth = 0
                requestedBufferHeight = 0
            }
        }
        updateBufferGeometryState(holder, bufferWidth, bufferHeight)
        if (previousHolder === holder && previous === surface) {
            if (codec == null && bufferGeometryReady) drainPendingKeyframe()
            return@synchronized
        }

        val activeCodec = codec
        if (activeCodec != null) {
            if (!bufferGeometryReady) {
                // Wait for the matching surfaceChanged callback before
                // rebinding; otherwise Android 9 vendors may retain the old
                // BufferQueue geometry.
                stopCodec()
                return@synchronized
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    activeCodec.setOutputSurface(surface)
                    Log.i(TAG, "Mirror codec output surface rebound")
                    return@synchronized
                } catch (error: Exception) {
                    Log.w(TAG, "Codec surface rebind rejected; waiting for keyframe", error)
                }
            }
            stopCodec()
        }
        if (bufferGeometryReady) drainPendingKeyframe()
    }

    fun clearSurface(holder: SurfaceHolder) = synchronized(lock) {
        if (displayHolder !== holder) return@synchronized
        displayHolder = null
        displaySurface = null
        requestedBufferWidth = 0
        requestedBufferHeight = 0
        bufferGeometryReady = false
        bufferGeometryRequestPending = false
        // Do not release the Activity-owned Surface. The codec must stop before
        // SurfaceView destroys its BufferQueue, otherwise the next window can
        // inherit a stale producer and remain black.
        stopCodec()
    }

    /**
     * The view rectangle and the BufferQueue geometry are independent. A
     * floating Activity may be 1230x810 while the sender supplies a 1920x1080
     * access unit. Qualcomm's direct Surface decoder requires the producer
     * buffer to be allocated at the coded size; SurfaceFlinger then scales it
     * into the view rectangle. Keeping this contract here avoids leaking
     * window dimensions into the AirPlay protocol or codec selection layers.
     */
    private fun applySurfaceBufferGeometry() {
        val holder = displayHolder ?: return
        if (!holder.surface.isValid || videoWidth <= 0 || videoHeight <= 0) return
        if (requestedBufferWidth == videoWidth && requestedBufferHeight == videoHeight) {
            if (!bufferGeometryRequestPending) bufferGeometryReady = true
            return
        }
        bufferGeometryReady = false
        try {
            holder.setFixedSize(videoWidth, videoHeight)
            requestedBufferWidth = videoWidth
            requestedBufferHeight = videoHeight
            bufferGeometryRequestPending = true
            Log.i(TAG, "Mirror Surface buffer geometry=${videoWidth}x$videoHeight")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to set mirror Surface buffer geometry", error)
        }
    }

    private fun resetSurfaceBufferGeometry() {
        val holder = displayHolder ?: return
        bufferGeometryReady = false
        bufferGeometryRequestPending = false
        if (!holder.surface.isValid) return
        if (requestedBufferWidth == 0 && requestedBufferHeight == 0) return
        try {
            holder.setSizeFromLayout()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to restore mirror Surface layout geometry", error)
        } finally {
            requestedBufferWidth = 0
            requestedBufferHeight = 0
        }
    }

    private fun updateBufferGeometryState(
        holder: SurfaceHolder,
        callbackWidth: Int,
        callbackHeight: Int,
    ) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            bufferGeometryReady = true
            return
        }
        val observedWidth = callbackWidth.takeIf { it > 0 }
            ?: holder.surfaceFrame.width()
        val observedHeight = callbackHeight.takeIf { it > 0 }
            ?: holder.surfaceFrame.height()
        bufferGeometryReady = observedWidth == videoWidth && observedHeight == videoHeight
        if (bufferGeometryReady) {
            bufferGeometryRequestPending = false
        } else if (bufferGeometryRequestPending) {
            applySurfaceBufferGeometry()
        } else {
            // A later window-layout callback may report the view rectangle
            // while the fixed producer geometry remains unchanged.
            bufferGeometryReady = true
        }
        Log.i(
            TAG,
            "Mirror Surface geometry observed=${observedWidth}x$observedHeight " +
                "target=${videoWidth}x$videoHeight ready=$bufferGeometryReady",
        )
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _updateStats(data.size)

        synchronized(lock) {
            val accessUnit = AnnexBAccessUnitInspector.inspect(data, isH265)
            val observedFps = observeInputRate(ntpTimeNs)
            if (accessUnit.isRandomAccess || accessUnit.hasCodecConfiguration) {
                rememberKeyframe(data, ntpTimeNs, isH265, accessUnit.hasCodecConfiguration)
            }
            if (videoWidth == 0 || videoHeight == 0) return
            if (!hasValidSurface() || !bufferGeometryReady) {
                return
            }

            if (softwareFallbackActive && observedFps != null &&
                observedFps > VideoDecodePolicy.SOFTWARE_MIRROR_MAX_FPS
            ) {
                Log.e(
                    TAG,
                    "Software mirror decoder cannot sustain stream: " +
                        "${videoWidth}x$videoHeight @${observedFps}fps",
                )
                stopCodec()
                return
            }

            if (codec != null && isH265 != currentH265) {
                stopCodec()
                resetInputRate()
                pendingKeyframe = null
            }
            if (codec == null && !accessUnit.hasCodecConfiguration) {
                // A later IDR often omits SPS/PPS/VPS. Re-submit the retained
                // complete decoder bootstrap before accepting it.
                drainPendingKeyframe()
                if (codec == null || currentH265 != isH265) return
            }

            try {
                if (codec == null) startCodec(isH265, data.size)
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun rememberKeyframe(
        data: ByteArray,
        ntpTimeNs: Long,
        isH265: Boolean,
        hasCodecConfiguration: Boolean,
    ) {
        if (data.size > MAX_PENDING_KEYFRAME_BYTES) return
        val retained = pendingKeyframe
        if (retained == null || retained.isH265 != isH265 ||
            hasCodecConfiguration || !retained.hasCodecConfiguration
        ) {
            pendingKeyframe = PendingFrame(
                data.copyOf(),
                ntpTimeNs,
                isH265,
                hasCodecConfiguration,
            )
        }
    }

    private fun drainPendingKeyframe() {
        val pending = pendingKeyframe ?: return
        if (!hasValidSurface() || !bufferGeometryReady || videoWidth <= 0 || videoHeight <= 0) return
        try {
            if (codec == null || currentH265 != pending.isH265) {
                stopCodec()
                startCodec(pending.isH265, pending.data.size)
            }
            _feedToCodec(pending.data, pending.ntpTimeNs)
            drainOutput()
            Log.i(TAG, "Pending mirror keyframe submitted after surface bind")
        } catch (error: Exception) {
            Log.w(TAG, "Pending mirror keyframe could not be submitted", error)
            stopCodec()
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                require(data.size <= buf.remaining()) {
                    "Mirror access unit ${data.size} exceeds codec input ${buf.remaining()}"
                }
                buf.put(data)
                val rawPtsUs = if (ntpTimeNs > 0L) ntpTimeNs / 1000L else 0L
                val ptsUs = if (lastQueuedPtsUs == Long.MIN_VALUE) {
                    rawPtsUs
                } else {
                    max(rawPtsUs, lastQueuedPtsUs + 1L)
                }
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                lastQueuedPtsUs = ptsUs
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun hasValidSurface(): Boolean = displaySurface?.isValid == true

    private fun startCodec(h265: Boolean, requiredInputBytes: Int) {
        val surface = displaySurface?.takeIf { it.isValid }
            ?: throw IllegalStateException("Mirror SurfaceView is not ready")
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val profile = checkNotNull(decoderProfile) {
            "AirPlay mirror decoder profile was not configured"
        }

        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        require(requiredInputBytes <= MAX_CODEC_INPUT_BYTES) {
            "Mirror access unit is too large: $requiredInputBytes"
        }
        val uncompressedFrameBytes = videoWidth.toLong() * videoHeight.toLong() * 3L / 2L
        val maxInput = maxOf(
            MIN_CODEC_INPUT_BYTES.toLong(),
            uncompressedFrameBytes,
            requiredInputBytes.toLong(),
        ).coerceAtMost(MAX_CODEC_INPUT_BYTES.toLong()).toInt()
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput)
        // Android 9 Qualcomm builds reject the optional realtime priority key;
        // newer platforms can use it without changing the stream contract.
        if (realtimeDecoderPriority && Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (operatingRateHint && Build.VERSION.SDK_INT >= 29) {
            // Android 9 Qualcomm builds reject this optional key. Only pass it
            // on platforms whose codec contract explicitly supports it.
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, TARGET_OPERATING_RATE)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
        if (lowLatency && Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        firstFrameQueued = false
        lastQueuedPtsUs = Long.MIN_VALUE
        val candidates = profile.candidatesByMime[mime].orEmpty()
        val hardware = VideoDecodePolicy.hardwareCandidates(candidates)
        var lastFailure: Exception? = null
        for (candidate in hardware) {
            try {
                startCandidate(candidate, format, surface, h265)
                Log.i(
                    TAG,
                    "Mirror hardware codec started: mime=$mime codec=${candidate.name} " +
                        "stream=${videoWidth}x$videoHeight " +
                        "advertisedMaxFPS=${profile.advertisedMaxFrameRate} " +
                        "reported=${candidate.reportedMaxWidth}x${candidate.reportedMaxHeight}" +
                        "@${candidate.reportedMaxFrameRate}, " +
                        "surfaceAdvertised=${candidate.surfaceFormatAdvertised}",
                )
                softwareFallbackActive = false
                return
            } catch (error: Exception) {
                lastFailure = error
                Log.w(TAG, "Mirror hardware codec rejected stream: ${candidate.name}", error)
            }
        }

        val softwareAllowed = VideoDecodePolicy.allowsSoftwareMirror(
            videoWidth,
            videoHeight,
            observedInputFps.takeIf { it > 0 },
        )
        if (softwareAllowed) {
            for (candidate in VideoDecodePolicy.softwareCandidates(candidates)) {
                try {
                    startCandidate(candidate, format, surface, h265)
                    Log.w(
                        TAG,
                        "Mirror software fallback started: mime=$mime codec=${candidate.name} " +
                            "stream=${videoWidth}x$videoHeight observedFPS=${observedInputFps}",
                    )
                    softwareFallbackActive = true
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    Log.w(TAG, "Mirror software codec rejected stream: ${candidate.name}", error)
                }
            }
        }

        val attempted = hardware.joinToString { it.name }.ifEmpty { "none" }
        val reason = if (softwareAllowed) {
            "no compatible Surface software decoder"
        } else {
            "software fallback forbidden above 1280x720@30"
        }
        throw IllegalStateException(
            "No usable mirror decoder for $mime ${videoWidth}x$videoHeight; " +
                "attempted=$attempted; $reason",
            lastFailure,
        )
    }

    private fun startCandidate(
        candidate: VideoDecoderCandidate,
        format: MediaFormat,
        surface: Surface,
        h265: Boolean,
    ) {
        try {
            _startDecoder(MediaCodec.createByCodecName(candidate.name), format, surface, h265)
        } catch (primary: Exception) {
            // Vendor implementations sometimes reject optional keys even when
            // the same stream is valid. Retry the same named codec with only
            // the mandatory MIME/size/input buffer contract before moving to a
            // different codec; configure/start remains the authority.
            Log.w(TAG, "Codec ${candidate.name} rejected tuned format; retrying conservative format", primary)
            val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val conservative = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
            conservative.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_CODEC_INPUT_BYTES)
            _startDecoder(MediaCodec.createByCodecName(candidate.name), conservative, surface, h265)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        lastQueuedPtsUs = Long.MIN_VALUE
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        codecName = ""
        softwareFallbackActive = false
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Decoder output format: ${c.outputFormat}")
                continue
            }
            if (idx < 0) break
            _recordOutputFrameTime()
            renderedFrameCount++
            if (renderedFrameCount == 1L) {
                Log.i(
                    TAG,
                    "Mirror first decoded frame: codec=$codecName " +
                        "buffer=${videoWidth}x$videoHeight ptsUs=${info.presentationTimeUs}",
                )
            }
            if (scheduledOutputBufferRelease) {
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                val targetNs = _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L
                val nowNs = System.nanoTime()
                // A delayed or malformed sender timestamp must not leave the
                // SurfaceView waiting seconds for a frame. Render immediately
                // when the target is outside a bounded pacing window.
                if (targetNs in (nowNs - LATE_FRAME_TOLERANCE_NS)..(nowNs + MAX_SCHEDULE_AHEAD_NS)) {
                    c.releaseOutputBuffer(idx, targetNs)
                } else {
                    c.releaseOutputBuffer(idx, true)
                }
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    /**
     * Clears decoder state. Remote output teardown can skip restoring layout
     * geometry because the mirror SurfaceView is hidden by the session state
     * before the next stream claims it; full surface destruction still uses the
     * default layout restoration path.
     */
    fun reset(restoreSurfaceGeometry: Boolean = true) = synchronized(lock) {
        stopCodec()
        if (restoreSurfaceGeometry) {
            resetSurfaceBufferGeometry()
        } else {
            requestedBufferWidth = 0
            requestedBufferHeight = 0
            bufferGeometryReady = false
            bufferGeometryRequestPending = false
        }
        pendingKeyframe = null
        videoWidth = 0
        videoHeight = 0
        resetInputRate()
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        renderedFrameCount = 0
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
    }

    fun release() = synchronized(lock) {
        reset()
        displayHolder = null
        displaySurface = null
    }

    private fun observeInputRate(ntpTimeNs: Long): Int? {
        if (ntpTimeNs <= 0L) return observedInputFps.takeIf { it > 0 }
        val ptsUs = ntpTimeNs / 1000L
        val previous = inputRateLastPtsUs
        inputRateLastPtsUs = ptsUs
        if (previous == Long.MIN_VALUE || ptsUs <= previous || ptsUs - previous > MAX_INPUT_RATE_GAP_US) {
            inputRateSampleCount = 1
            inputRateSamplesUs[0] = ptsUs
            return observedInputFps.takeIf { it > 0 }
        }
        val index = inputRateSampleCount % inputRateSamplesUs.size
        inputRateSamplesUs[index] = ptsUs
        inputRateSampleCount++
        if (inputRateSampleCount >= MIN_INPUT_RATE_SAMPLES) {
            val oldestIndex = (inputRateSampleCount - MIN_INPUT_RATE_SAMPLES) % inputRateSamplesUs.size
            val oldest = inputRateSamplesUs[oldestIndex]
            val durationUs = ptsUs - oldest
            if (durationUs > 0L) {
                observedInputFps = (((MIN_INPUT_RATE_SAMPLES - 1) * 1_000_000L) / durationUs)
                    .toInt()
                    .coerceAtLeast(1)
            }
        }
        return observedInputFps.takeIf { it > 0 }
    }

    private fun resetInputRate() {
        inputRateLastPtsUs = Long.MIN_VALUE
        inputRateSampleCount = 0
        observedInputFps = 0
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
        private const val MAX_PENDING_KEYFRAME_BYTES = 8 * 1024 * 1024
        private const val MIN_CODEC_INPUT_BYTES = MAX_PENDING_KEYFRAME_BYTES
        private const val MAX_CODEC_INPUT_BYTES = MAX_PENDING_KEYFRAME_BYTES
        private const val TARGET_OPERATING_RATE = 60
        private const val INPUT_RATE_SAMPLE_COUNT = 16
        private const val MIN_INPUT_RATE_SAMPLES = 8
        private const val MAX_INPUT_RATE_GAP_US = 2_000_000L
        private const val LATE_FRAME_TOLERANCE_NS = 2_000_000L
        private const val MAX_SCHEDULE_AHEAD_NS = 100_000_000L

        internal fun resolveMirrorDisplayProfile(
            width: Int,
            height: Int,
            displayRefreshHz: Int,
        ): MirrorDecoderProfile {
            val candidatesByMime = listOf(
                VideoDecodePolicy.AVC_MIME,
                VideoDecodePolicy.HEVC_MIME,
            )
                .associateWith { mime -> decoderCandidates(mime, width, height) }
            val supportsH265 = VideoDecodePolicy.hasHardwareCandidate(
                candidatesByMime,
                VideoDecodePolicy.HEVC_MIME,
            )
            val maxFrameRate = VideoDecodePolicy.maxAdvertisedFps(
                panelRefreshHz = displayRefreshHz,
                candidatesByMime = candidatesByMime,
                enableH265 = supportsH265,
            )
            return MirrorDecoderProfile(
                panelWidth = width,
                panelHeight = height,
                panelRefreshHz = displayRefreshHz,
                supportsH265 = supportsH265,
                advertisedMaxFrameRate = maxFrameRate,
                candidatesByMime = candidatesByMime,
            )
        }

        private fun decoderCandidates(
            mime: String,
            panelWidth: Int,
            panelHeight: Int,
        ): List<VideoDecoderCandidate> = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot(MediaCodecInfo::isEncoder)
                .mapNotNull { info -> decoderCandidate(info, mime, panelWidth, panelHeight) }
                .distinctBy(VideoDecoderCandidate::name)
                .toList()
                .let { VideoDecodePolicy.orderCandidates(it) }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to enumerate decoder candidates for $mime", error)
            emptyList()
        }

        private fun decoderCandidate(
            info: MediaCodecInfo,
            mime: String,
            panelWidth: Int,
            panelHeight: Int,
        ): VideoDecoderCandidate? {
            val supportedMime = info.supportedTypes.firstOrNull {
                it.equals(mime, ignoreCase = true)
            } ?: return null
            return try {
                val capabilities = info.getCapabilitiesForType(supportedMime)
                val videoCapabilities = capabilities.videoCapabilities ?: return null
                val maxFrameRate = supportedFrameRate(
                    videoCapabilities,
                    panelWidth,
                    panelHeight,
                )
                VideoDecoderCandidate(
                    name = info.name,
                    mimeType = mime,
                    kind = decoderKind(info),
                    reportedMaxWidth = videoCapabilities.supportedWidths.upper,
                    reportedMaxHeight = videoCapabilities.supportedHeights.upper,
                    reportedMaxFrameRate = maxFrameRate,
                    surfaceFormatAdvertised = capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    ),
                )
            } catch (error: Exception) {
                Log.w(TAG, "Ignoring unusable decoder capability: ${info.name} $mime", error)
                null
            }
        }

        private fun supportedFrameRate(
            capabilities: MediaCodecInfo.VideoCapabilities,
            width: Int,
            height: Int,
        ): Int {
            if (width <= 0 || height <= 0) return 0
            return sequenceOf(width to height, height to width)
                .distinct()
                .mapNotNull { (candidateWidth, candidateHeight) ->
                    try {
                        if (!capabilities.isSizeSupported(candidateWidth, candidateHeight)) null
                        else capabilities.getSupportedFrameRatesFor(candidateWidth, candidateHeight).upper.toInt()
                    } catch (error: Exception) {
                        Log.w(
                            TAG,
                            "Failed to query decoder rate for ${candidateWidth}x$candidateHeight",
                            error,
                        )
                        null
                    }
                }
                .maxOrNull() ?: 0
        }

        private fun decoderKind(info: MediaCodecInfo): VideoDecoderKind {
            if (Build.VERSION.SDK_INT >= 29) {
                return if (info.isSoftwareOnly) VideoDecoderKind.SOFTWARE else VideoDecoderKind.HARDWARE
            }
            val name = info.name.lowercase()
            return when {
                name.startsWith("omx.qcom.") -> VideoDecoderKind.HARDWARE
                name.startsWith("omx.google.") || name.startsWith("c2.android.") ->
                    VideoDecoderKind.SOFTWARE
                else -> VideoDecoderKind.SOFTWARE
            }
        }
    }

    private data class PendingFrame(
        val data: ByteArray,
        val ntpTimeNs: Long,
        val isH265: Boolean,
        val hasCodecConfiguration: Boolean,
    )

    private fun MirrorDecoderProfile.codecSummary(): String =
        candidatesByMime.entries.joinToString(separator = ";") { (mime, candidates) ->
            "$mime=[${candidates.joinToString { candidate ->
                "${candidate.name}:${candidate.kind.name.lowercase()}@" +
                    "${candidate.reportedMaxWidth}x${candidate.reportedMaxHeight}" +
                    "@${candidate.reportedMaxFrameRate}:" +
                    "surfaceAdvertised=${candidate.surfaceFormatAdvertised}"
            }}]"
        }

}
