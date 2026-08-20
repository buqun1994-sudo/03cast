package com.ninepointnine.desktopcast.dlna

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GenaEventPublisher(
    private val registry: GenaSubscriptionRegistry,
    private val snapshot: () -> DlnaPlaybackSnapshot,
) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "dlna-gena").apply { isDaemon = true }
    }

    fun publishInitial(sid: String) {
        val subscription = registry.next(sid) ?: return
        val current = snapshot()
        executor.execute { send(subscription, body(subscription.service, current)) }
    }

    fun publish(service: DlnaService) {
        val current = snapshot()
        registry.nextFor(service).forEach { subscription ->
            executor.execute { send(subscription, body(service, current)) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun send(subscription: GenaSubscription, body: String) {
        val uri = subscription.callback
        val port = if (uri.port >= 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.ifBlank { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val hostHeader = if (':' in uri.host) "[${uri.host}]:$port" else "${uri.host}:$port"
        val request = buildString {
            append("NOTIFY $path HTTP/1.1\r\n")
            append("HOST: $hostHeader\r\n")
            append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
            append("NT: upnp:event\r\n")
            append("NTS: upnp:propchange\r\n")
            append("SID: ${subscription.sid}\r\n")
            append("SEQ: ${subscription.sequence}\r\n")
            append("CONTENT-LENGTH: ${bytes.size}\r\n")
            append("CONNECTION: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().run {
                    write(request)
                    write(bytes)
                    flush()
                }
                socket.getInputStream().read(ByteArray(256))
            }
        }.onFailure { error ->
            Log.w(TAG, "GENA notify failed for ${subscription.sid}: ${error.message}")
        }
    }

    private fun body(service: DlnaService, state: DlnaPlaybackSnapshot): String {
        val properties = when (service) {
            DlnaService.AV_TRANSPORT -> {
                val event = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0"><TransportState val="${state.transportState.wireValue}"/><CurrentTrackURI val="${DlnaXml.escape(state.media?.uri.orEmpty())}"/><CurrentTrackDuration val="${DlnaXml.formatTime(state.durationMs)}"/><RelativeTimePosition val="${DlnaXml.formatTime(state.positionMs)}"/></InstanceID></Event>"""
                "<e:property><LastChange>${DlnaXml.escape(event)}</LastChange></e:property>"
            }
            DlnaService.RENDERING_CONTROL -> {
                val event = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0"><Volume channel="Master" val="${state.volume}"/><Mute channel="Master" val="${if (state.muted) 1 else 0}"/></InstanceID></Event>"""
                "<e:property><LastChange>${DlnaXml.escape(event)}</LastChange></e:property>"
            }
            DlnaService.CONNECTION_MANAGER ->
                "<e:property><SinkProtocolInfo>${DlnaXml.escape(DlnaDescriptions.sinkProtocolInfo)}</SinkProtocolInfo></e:property>" +
                    "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>"
        }
        return """<?xml version="1.0" encoding="utf-8"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">$properties</e:propertyset>"""
    }

    private companion object {
        const val TAG = "GenaEventPublisher"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 3_000
    }
}
