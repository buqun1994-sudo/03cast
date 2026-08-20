package com.ninepointnine.desktopcast.dlna

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaSoapDispatcherTest {
    @Test
    fun parsesNamespacedSetUriAndMetadata() {
        val player = FakePlayer()
        val dispatcher = DlnaSoapDispatcher(player)
        val metadata = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item><dc:title>测试影片</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*">http://host/video.mp4</res></item></DIDL-Lite>"""
        val body = envelope(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>http://fallback/video</CurrentURI>" +
                "<CurrentURIMetaData>${DlnaXml.escape(metadata)}</CurrentURIMetaData>",
        )

        val response = dispatcher.dispatch(DlnaService.AV_TRANSPORT, body)

        assertEquals("测试影片", player.state.media?.title)
        assertEquals(DlnaMediaKind.VIDEO, player.state.media?.kind)
        assertEquals("http://host/video.mp4", player.state.media?.uri)
        assertEquals(MimeTypes.VIDEO_MP4, player.state.media?.mimeType)
        assertTrue("SetAVTransportURIResponse" in response)
    }

    @Test
    fun preservesHlsProtocolInfoForPlayback() {
        val player = FakePlayer()
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item><upnp:class xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">object.item.videoItem</upnp:class><res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">https://media.example/opaque?id=1</res></item></DIDL-Lite>"""

        DlnaSoapDispatcher(player).dispatch(
            DlnaService.AV_TRANSPORT,
            envelope(
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>https://fallback.example/video</CurrentURI>" +
                    "<CurrentURIMetaData>${DlnaXml.escape(metadata)}</CurrentURIMetaData>",
            ),
        )

        // The DLNA parser preserves the sender's protocolInfo verbatim;
        // MediaMimeResolver normalizes it at the Media3 boundary.
        assertEquals("application/vnd.apple.mpegurl", player.state.media?.mimeType)
        assertEquals(DlnaMediaKind.VIDEO, player.state.media?.kind)
    }

    @Test
    fun playPauseSeekAndQueryShareState() {
        val player = FakePlayer()
        val dispatcher = DlnaSoapDispatcher(player)
        dispatcher.dispatch(
            DlnaService.AV_TRANSPORT,
            envelope("SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>http://host/video.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"),
        )
        dispatcher.dispatch(DlnaService.AV_TRANSPORT, envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"))
        dispatcher.dispatch(DlnaService.AV_TRANSPORT, envelope("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:23.500</Target>"))

        val result = dispatcher.dispatch(DlnaService.AV_TRANSPORT, envelope("GetPositionInfo", "<InstanceID>0</InstanceID>"))

        assertEquals(83_500, player.state.positionMs)
        assertEquals(DlnaTransportState.PLAYING, player.state.transportState)
        assertTrue("<RelTime>0:01:23</RelTime>" in result)
    }

    @Test
    fun metadataPlaceholderCannotOverrideCurrentUri() {
        val player = FakePlayer()
        val dispatcher = DlnaSoapDispatcher(player)
        val currentUri = "https://media.example/video.mp4?token=abc"
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item><res protocolInfo="http-get:*:video/*:*">%@</res></item></DIDL-Lite>"""

        dispatcher.dispatch(
            DlnaService.AV_TRANSPORT,
            envelope(
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${DlnaXml.escape(currentUri)}</CurrentURI>" +
                    "<CurrentURIMetaData>${DlnaXml.escape(metadata)}</CurrentURIMetaData>",
            ),
        )

        assertEquals(currentUri, player.state.media?.uri)
    }

    @Test
    fun muteAndVolumeActuallyReachPlayer() {
        val player = FakePlayer()
        val dispatcher = DlnaSoapDispatcher(player)

        dispatcher.dispatch(DlnaService.RENDERING_CONTROL, envelopeFor(DlnaService.RENDERING_CONTROL, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>77</DesiredVolume>"))
        dispatcher.dispatch(DlnaService.RENDERING_CONTROL, envelopeFor(DlnaService.RENDERING_CONTROL, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>"))

        assertEquals(77, player.state.volume)
        assertTrue(player.state.muted)
    }

    @Test(expected = DlnaControlException::class)
    fun rejectsExternalEntities() {
        val player = FakePlayer()
        val body = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY file SYSTEM "file:///etc/passwd">]><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>&file;</InstanceID></u:Play></s:Body></s:Envelope>"""
        DlnaSoapDispatcher(player).dispatch(DlnaService.AV_TRANSPORT, body)
    }

    private fun envelope(action: String, arguments: String) =
        envelopeFor(DlnaService.AV_TRANSPORT, action, arguments)

    private fun envelopeFor(service: DlnaService, action: String, arguments: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:$action xmlns:u="${service.serviceType}">$arguments</u:$action></s:Body></s:Envelope>"""

    private class FakePlayer : DlnaPlaybackController {
        var state = DlnaPlaybackSnapshot()

        override fun setMedia(media: DlnaMedia) {
            state = state.copy(media = media, transportState = DlnaTransportState.STOPPED)
        }

        override fun play() {
            state = state.copy(transportState = DlnaTransportState.PLAYING)
        }

        override fun pause() {
            state = state.copy(transportState = DlnaTransportState.PAUSED)
        }

        override fun stop() {
            state = state.copy(transportState = DlnaTransportState.STOPPED)
        }

        override fun seekTo(positionMs: Long) {
            state = state.copy(positionMs = positionMs)
        }

        override fun setVolume(percent: Int) {
            state = state.copy(volume = percent)
        }

        override fun setMuted(muted: Boolean) {
            state = state.copy(muted = muted)
        }

        override fun snapshot(): DlnaPlaybackSnapshot = state
    }
}
