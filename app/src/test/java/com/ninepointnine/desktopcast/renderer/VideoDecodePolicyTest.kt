package com.ninepointnine.desktopcast.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecodePolicyTest {

    @Test
    fun ordersHardwareBeforeSoftwareRegardlessOfInputOrder() {
        val software = candidate(
            name = "OMX.google.h264.decoder",
            kind = VideoDecoderKind.SOFTWARE,
        )
        val hardware = candidate(
            name = "OMX.qcom.video.decoder.avc",
            kind = VideoDecoderKind.HARDWARE,
        )

        assertEquals(
            listOf(hardware, software),
            VideoDecodePolicy.orderCandidates(listOf(software, hardware)),
        )
    }

    @Test
    fun advertisesSixtyWhenHardwareRateIsKnownAbovePanelRate() {
        val candidates = mapOf(
            VideoDecodePolicy.AVC_MIME to listOf(
                candidate(
                    name = "OMX.qcom.video.decoder.avc",
                    mimeType = VideoDecodePolicy.AVC_MIME,
                    kind = VideoDecoderKind.HARDWARE,
                    reportedMaxFrameRate = 120,
                ),
            ),
            VideoDecodePolicy.HEVC_MIME to listOf(
                candidate(
                    name = "OMX.qcom.video.decoder.hevc",
                    mimeType = VideoDecodePolicy.HEVC_MIME,
                    kind = VideoDecoderKind.HARDWARE,
                    reportedMaxFrameRate = 240,
                ),
            ),
        )

        assertEquals(
            60,
            VideoDecodePolicy.maxAdvertisedFps(
                panelRefreshHz = 60,
                candidatesByMime = candidates,
                enableH265 = true,
            ),
        )
    }

    @Test
    fun advertisesThirtyWhenHardwareCapabilityIsUnknown() {
        val candidates = mapOf(
            VideoDecodePolicy.AVC_MIME to listOf(
                candidate(
                    name = "OMX.google.h264.decoder",
                    kind = VideoDecoderKind.SOFTWARE,
                ),
            ),
        )

        assertEquals(
            30,
            VideoDecodePolicy.maxAdvertisedFps(
                panelRefreshHz = 60,
                candidatesByMime = candidates,
                enableH265 = false,
            ),
        )
    }

    @Test
    fun softwareOnlyHevcDoesNotEnableHevcMirroring() {
        val candidates = mapOf(
            VideoDecodePolicy.HEVC_MIME to listOf(
                candidate(
                    name = "OMX.google.hevc.decoder",
                    mimeType = VideoDecodePolicy.HEVC_MIME,
                    kind = VideoDecoderKind.SOFTWARE,
                ),
            ),
        )

        assertFalse(
            VideoDecodePolicy.hasHardwareCandidate(
                candidates,
                VideoDecodePolicy.HEVC_MIME,
            ),
        )
    }

    @Test
    fun nonSurfaceHardwareStillParticipatesWhenMetadataIsBroken() {
        val candidates = mapOf(
            VideoDecodePolicy.AVC_MIME to listOf(
                candidate(
                    name = "OMX.qcom.video.decoder.avc",
                    kind = VideoDecoderKind.HARDWARE,
                    surfaceFormatAdvertised = false,
                ),
            ),
        )

        assertTrue(
            VideoDecodePolicy.hasHardwareCandidate(
                candidates,
                VideoDecodePolicy.AVC_MIME,
            ),
        )
    }

    @Test
    fun unknownRateAtPanelSizeUsesPanelRateUntilRuntimeProbe() {
        val candidates = mapOf(
            VideoDecodePolicy.HEVC_MIME to listOf(
                candidate(
                    name = "OMX.qcom.video.decoder.hevc",
                    mimeType = VideoDecodePolicy.HEVC_MIME,
                    kind = VideoDecoderKind.HARDWARE,
                    reportedMaxWidth = 384,
                    reportedMaxHeight = 384,
                    reportedMaxFrameRate = 0,
                    surfaceFormatAdvertised = false,
                ),
            ),
        )

        assertTrue(
            VideoDecodePolicy.hasHardwareCandidate(
                candidates,
                VideoDecodePolicy.HEVC_MIME,
            ),
        )
        val allCandidates = mapOf(
            VideoDecodePolicy.AVC_MIME to listOf(
                candidate(
                    name = "OMX.qcom.video.decoder.avc",
                    kind = VideoDecoderKind.HARDWARE,
                    reportedMaxWidth = 256,
                    reportedMaxHeight = 256,
                    reportedMaxFrameRate = 0,
                    surfaceFormatAdvertised = false,
                ),
            ),
            VideoDecodePolicy.HEVC_MIME to candidates.getValue(VideoDecodePolicy.HEVC_MIME),
        )
        assertEquals(
            60,
            VideoDecodePolicy.maxAdvertisedFps(
                panelRefreshHz = 60,
                candidatesByMime = allCandidates,
                enableH265 = true,
            ),
        )
    }

    @Test
    fun allowsSoftwareMirrorAt720pThirty() {
        assertTrue(VideoDecodePolicy.allowsSoftwareMirror(1280, 720, 30))
    }

    @Test
    fun allowsSmallSoftwareMirrorBeforeRateHasBeenObserved() {
        assertTrue(VideoDecodePolicy.allowsSoftwareMirror(1280, 720, null))
    }

    @Test
    fun rejectsSoftwareMirrorAboveResolutionOrFrameRateLimit() {
        assertFalse(VideoDecodePolicy.allowsSoftwareMirror(1920, 1080, 30))
        assertFalse(VideoDecodePolicy.allowsSoftwareMirror(1280, 720, 60))
    }

    @Test
    fun recreatesCodecForAndroid9VendorSurfaceHandoff() {
        assertTrue(
            SurfaceHandoffPolicy.forceCodecRecreate(
                sdkInt = 28,
                codecName = "OMX.qcom.video.decoder.avc",
            ),
        )
        assertFalse(
            SurfaceHandoffPolicy.forceCodecRecreate(
                sdkInt = 28,
                codecName = "OMX.google.h264.decoder",
            ),
        )
        assertFalse(
            SurfaceHandoffPolicy.forceCodecRecreate(
                sdkInt = 29,
                codecName = "OMX.qcom.video.decoder.avc",
            ),
        )
    }

    @Test
    fun allowsPortraitSoftwareMirrorWithinSamePixelBoundary() {
        assertTrue(VideoDecodePolicy.allowsSoftwareMirror(720, 1280, 30))
    }

    private fun candidate(
        name: String,
        mimeType: String = VideoDecodePolicy.AVC_MIME,
        kind: VideoDecoderKind,
        reportedMaxWidth: Int = 3840,
        reportedMaxHeight: Int = 2160,
        reportedMaxFrameRate: Int = 60,
        surfaceFormatAdvertised: Boolean = true,
    ) = VideoDecoderCandidate(
        name = name,
        mimeType = mimeType,
        kind = kind,
        reportedMaxWidth = reportedMaxWidth,
        reportedMaxHeight = reportedMaxHeight,
        reportedMaxFrameRate = reportedMaxFrameRate,
        surfaceFormatAdvertised = surfaceFormatAdvertised,
    )
}
