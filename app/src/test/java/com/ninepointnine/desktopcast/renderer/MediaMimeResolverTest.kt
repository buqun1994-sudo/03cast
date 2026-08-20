package com.ninepointnine.desktopcast.renderer

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMimeResolverTest {
    @Test
    fun mapsDeclaredHlsMime() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            MediaMimeResolver.resolve("https://media.example/stream?id=1", "application/vnd.apple.mpegurl"),
        )
    }

    @Test
    fun uriHlsMarkerWinsOverBroadVideoDeclaration() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            MediaMimeResolver.resolve("https://media.example/live.m3u8?token=abc", "video/*"),
        )
    }

    @Test
    fun decodesHlsQueryMarker() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            MediaMimeResolver.resolve("https://media.example/play?mime=application%2Fvnd.apple.mpegurl"),
        )
    }

    @Test
    fun mapsCommonContainerFromUri() {
        assertEquals(
            MimeTypes.VIDEO_MP4,
            MediaMimeResolver.resolve("https://media.example/video.mp4?token=abc"),
        )
        assertEquals(
            MimeTypes.VIDEO_MP2T,
            MediaMimeResolver.resolve("https://media.example/segment.ts"),
        )
    }

    @Test
    fun doesNotForceGenericOrUnknownMime() {
        assertNull(MediaMimeResolver.resolve("https://media.example/opaque", "video/*"))
        assertNull(MediaMimeResolver.resolve("https://media.example/opaque", "application/octet-stream"))
    }
}
