package com.tcrrry.desktopcast.renderer

/**
 * Describes one decoder candidate without exposing Android codec objects to
 * the selection policy or its JVM tests.
 *
 * Android 9 vendor codecs on the target car report incomplete capabilities.
 * The reported values are therefore diagnostic and ordering hints only. A
 * candidate is accepted for playback only after MediaCodec configure/start
 * succeeds against the real stream and Surface.
 */
internal enum class VideoDecoderKind {
    HARDWARE,
    SOFTWARE,
}

internal data class VideoDecoderCandidate(
    val name: String,
    val mimeType: String,
    val kind: VideoDecoderKind,
    val reportedMaxWidth: Int,
    val reportedMaxHeight: Int,
    val reportedMaxFrameRate: Int,
    val surfaceFormatAdvertised: Boolean,
) {
    /**
     * Returns the answer from Android's static metadata. It must never be
     * used as a playback hard gate on a vendor device.
     */
    fun reportedSupportsSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || reportedMaxWidth <= 0 || reportedMaxHeight <= 0) {
            return false
        }
        return (width <= reportedMaxWidth && height <= reportedMaxHeight) ||
            (height <= reportedMaxWidth && width <= reportedMaxHeight)
    }
}

/**
 * Runtime mirror profile. Display negotiation and decoder selection share this
 * immutable candidate snapshot, while the physical panel values remain a
 * separate contract from codec throughput.
 */
internal data class MirrorDecoderProfile(
    val panelWidth: Int,
    val panelHeight: Int,
    val panelRefreshHz: Int,
    val supportsH265: Boolean,
    val advertisedMaxFrameRate: Int,
    val candidatesByMime: Map<String, List<VideoDecoderCandidate>>,
)

internal object VideoDecodePolicy {
    const val AVC_MIME = "video/avc"
    const val HEVC_MIME = "video/hevc"

    const val MAX_MIRROR_ADVERTISED_FPS = 60
    const val UNKNOWN_MIRROR_ADVERTISED_FPS = 30
    const val SOFTWARE_MIRROR_MAX_FPS = 30
    const val SOFTWARE_MIRROR_MAX_LONG_SIDE = 1280
    const val SOFTWARE_MIRROR_MAX_SHORT_SIDE = 720

    /** Hardware candidates always precede software candidates. */
    fun orderCandidates(candidates: List<VideoDecoderCandidate>): List<VideoDecoderCandidate> =
        candidates.sortedWith(
            compareBy<VideoDecoderCandidate> {
                if (it.kind == VideoDecoderKind.HARDWARE) 0 else 1
            }
                // A listed surface format is a useful preference, never a gate.
                .thenBy { if (it.surfaceFormatAdvertised) 0 else 1 }
                .thenByDescending { it.reportedMaxFrameRate }
                .thenByDescending {
                    it.reportedMaxWidth.toLong() * it.reportedMaxHeight.toLong()
                }
                .thenBy { it.name },
        )

    /**
     * Returns every hardware candidate for the MIME. Size and color-format
     * metadata are intentionally ignored; Qualcomm's Android 9 table reports
     * 256x256 and no Surface format despite accepting real 1080p Surface input.
     */
    fun hardwareCandidates(candidates: List<VideoDecoderCandidate>): List<VideoDecoderCandidate> =
        orderCandidates(candidates).filter { it.kind == VideoDecoderKind.HARDWARE }

    /**
     * Software candidates are also selected by a real configure/start probe.
     * The caller applies the explicit small-stream policy before trying them.
     */
    fun softwareCandidates(candidates: List<VideoDecoderCandidate>): List<VideoDecoderCandidate> =
        orderCandidates(candidates).filter { it.kind == VideoDecoderKind.SOFTWARE }

    /**
     * AirPlay maxFPS is an upper bound for the sender, not a measured stream
     * rate. A present hardware candidate is enough to keep a broken vendor
     * frame-rate range from collapsing the display declaration to 30 fps; the
     * physical panel remains the final upper bound.
     */
    fun maxAdvertisedFps(
        panelRefreshHz: Int,
        candidatesByMime: Map<String, List<VideoDecoderCandidate>>,
        enableH265: Boolean,
    ): Int {
        if (panelRefreshHz <= 0) {
            return UNKNOWN_MIRROR_ADVERTISED_FPS
        }
        val panelCap = minOf(panelRefreshHz, MAX_MIRROR_ADVERTISED_FPS)
        val requiredMimes = buildList {
            add(AVC_MIME)
            if (enableH265) add(HEVC_MIME)
        }
        val perMimeUpper = requiredMimes.map { mime ->
            val hardware = hardwareCandidates(candidatesByMime[mime].orEmpty())
            if (hardware.isEmpty()) {
                null
            } else {
                // A zero/exceptional static rate means "unknown", not 0 fps.
                hardware.map { it.reportedMaxFrameRate }
                    .filter { it > 0 }
                    .maxOrNull()
                    ?.coerceAtMost(panelCap)
                    ?: panelCap
            }
        }
        if (perMimeUpper.any { it == null }) {
            return UNKNOWN_MIRROR_ADVERTISED_FPS.coerceAtMost(panelCap)
        }
        return minOf(panelCap, perMimeUpper.filterNotNull().minOrNull() ?: panelCap)
            .coerceAtLeast(1)
    }

    /**
     * Software decoding remains a compatibility path for small mirror
     * streams. At startup the real stream rate is not known yet, so null is
     * allowed; once timestamps provide an observation, the 30 fps ceiling is
     * enforced against that observation rather than the display declaration.
     */
    fun allowsSoftwareMirror(width: Int, height: Int, observedFps: Int?): Boolean {
        if (width <= 0 || height <= 0) return false
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        return longSide <= SOFTWARE_MIRROR_MAX_LONG_SIDE &&
            shortSide <= SOFTWARE_MIRROR_MAX_SHORT_SIDE &&
            (observedFps == null || observedFps <= SOFTWARE_MIRROR_MAX_FPS)
    }

    fun hasHardwareCandidate(
        candidatesByMime: Map<String, List<VideoDecoderCandidate>>,
        mimeType: String,
    ): Boolean = hardwareCandidates(candidatesByMime[mimeType].orEmpty()).isNotEmpty()
}

internal fun MirrorDecoderProfile.codecSummaryForRuntime(): String =
    candidatesByMime.entries.joinToString(separator = ";") { (mime, candidates) ->
        "$mime=${candidates.joinToString(",") { candidate ->
            "${candidate.name}:reported=${candidate.reportedMaxWidth}x" +
                "${candidate.reportedMaxHeight}@${candidate.reportedMaxFrameRate}" +
                ":surface=${candidate.surfaceFormatAdvertised}"
        }}"
    }
