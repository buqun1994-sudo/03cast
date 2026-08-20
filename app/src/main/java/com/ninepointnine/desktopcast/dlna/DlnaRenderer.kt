package com.ninepointnine.desktopcast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DlnaRenderer(
    private val context: Context,
    private val friendlyName: String,
    private val uuid: String,
    private val controller: DlnaPlaybackController,
    private val onFailure: (Throwable) -> Unit,
) {
    private val subscriptions = GenaSubscriptionRegistry()
    private val events = GenaEventPublisher(subscriptions, controller::snapshot)
    private val soap = DlnaSoapDispatcher(controller)
    private val clients: ExecutorService = Executors.newFixedThreadPool(6) { task ->
        Thread(task, "dlna-http-client").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dlna-announce").apply { isDaemon = true }
    }

    @Volatile private var running = false
    private var localAddress: Inet4Address? = null
    private var networkInterface: NetworkInterface? = null
    private var httpServer: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val isRunning: Boolean get() = running
    val descriptionUrl: String?
        get() = localAddress?.hostAddress?.let { "http://$it:${DlnaDescriptions.HTTP_PORT}/description.xml" }

    @Synchronized
    fun start(address: Inet4Address) {
        if (running && localAddress == address) return
        check(!running) { "DLNA renderer must be stopped before rebinding" }
        localAddress = address
        networkInterface = NetworkInterface.getByInetAddress(address)
            ?: throw IllegalStateException("No network interface for ${address.hostAddress}")
        acquireMulticastLock()
        try {
            startHttp(address)
            startSsdp(networkInterface!!)
            running = true
            Thread(::acceptLoop, "dlna-http-accept").apply { isDaemon = true }.start()
            Thread(::ssdpLoop, "dlna-ssdp").apply { isDaemon = true }.start()
            scheduler.execute(::sendAliveSafely)
            scheduler.scheduleAtFixedRate(::sendAliveSafely, 600, 600, TimeUnit.SECONDS)
            Log.i(TAG, "DLNA ready at $descriptionUrl")
        } catch (error: Throwable) {
            closeResources(sendByeBye = false)
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (!running && httpServer == null && ssdpSocket == null) return
        closeResources(sendByeBye = running)
        Log.i(TAG, "DLNA stopped")
    }

    fun publishTransportChanged() = events.publish(DlnaService.AV_TRANSPORT)
    fun publishRenderingChanged() = events.publish(DlnaService.RENDERING_CONTROL)

    private fun startHttp(address: Inet4Address) {
        httpServer = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, DlnaDescriptions.HTTP_PORT), HTTP_BACKLOG)
        }
    }

    private fun startSsdp(iface: NetworkInterface) {
        ssdpSocket = MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            timeToLive = 2
            networkInterface = iface
            joinGroup(InetSocketAddress(SSDP_GROUP, SSDP_PORT), iface)
        }
    }

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("03cast-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                httpServer?.accept() ?: break
            } catch (error: Throwable) {
                if (running) fail(error)
                break
            }
            clients.execute { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = HTTP_TIMEOUT_MS
            val output = client.getOutputStream()
            try {
                val request = DlnaHttpParser.read(client.getInputStream())
                route(request, output)
            } catch (error: DlnaHttpException) {
                writeResponse(output, error.status, reason(error.status), "text/plain", error.message.orEmpty())
            } catch (error: Throwable) {
                Log.w(TAG, "HTTP request failed", error)
                runCatching { writeResponse(output, 500, "Internal Server Error", "text/plain", "") }
            }
        }
    }

    private fun route(request: DlnaHttpRequest, output: OutputStream) {
        val path = request.target.substringBefore('?')
        when {
            request.method in setOf("GET", "HEAD") && path == "/description.xml" -> {
                val body = DlnaDescriptions.device(uuid, friendlyName, baseUrl())
                writeResponse(output, 200, "OK", XML_CONTENT_TYPE, body, request.method == "HEAD")
            }
            request.method in setOf("GET", "HEAD") && path.startsWith("/scpd/") -> {
                val service = DlnaService.fromPath(path.substringAfterLast('/').substringBefore('.'))
                    ?: return writeResponse(output, 404, "Not Found", "text/plain", "")
                writeResponse(
                    output,
                    200,
                    "OK",
                    XML_CONTENT_TYPE,
                    DlnaDescriptions.scpd(service),
                    request.method == "HEAD",
                )
            }
            request.method == "POST" && path.startsWith("/control/") -> {
                val service = DlnaService.fromPath(path.substringAfterLast('/'))
                    ?: return writeResponse(output, 404, "Not Found", "text/plain", "")
                handleSoap(service, request, output)
            }
            request.method == "SUBSCRIBE" && path.startsWith("/event/") -> {
                val service = DlnaService.fromPath(path.substringAfterLast('/'))
                    ?: return writeResponse(output, 404, "Not Found", "text/plain", "")
                handleSubscribe(service, request, output)
            }
            request.method == "UNSUBSCRIBE" && path.startsWith("/event/") ->
                handleUnsubscribe(request, output)
            request.method == "GET" && path == "/" ->
                writeResponse(output, 200, "OK", "text/plain; charset=utf-8", friendlyName)
            else -> writeResponse(output, 404, "Not Found", "text/plain", "")
        }
    }

    private fun handleSoap(service: DlnaService, request: DlnaHttpRequest, output: OutputStream) {
        try {
            val action = DlnaXml.parseSoapAction(request.bodyUtf8()).name
            val headerAction = request.headers["soapaction"]?.trim()?.trim('"')?.substringAfterLast('#')
            if (!headerAction.isNullOrBlank() && headerAction != action) {
                throw DlnaControlException(401, "SOAP action does not match body")
            }
            val response = soap.dispatch(service, request.bodyUtf8())
            writeResponse(output, 200, "OK", XML_CONTENT_TYPE, response)
            when (action) {
                "SetAVTransportURI", "Play", "Pause", "Stop", "Seek" -> publishTransportChanged()
                "SetVolume", "SetMute" -> publishRenderingChanged()
            }
        } catch (error: DlnaControlException) {
            writeResponse(
                output,
                500,
                "Internal Server Error",
                XML_CONTENT_TYPE,
                DlnaXml.fault(error.upnpCode, error.message.orEmpty()),
            )
        }
    }

    private fun handleSubscribe(
        service: DlnaService,
        request: DlnaHttpRequest,
        output: OutputStream,
    ) {
        try {
            val sid = request.headers["sid"]
            val subscription = if (sid != null) {
                if (request.headers.containsKey("callback") || request.headers.containsKey("nt")) {
                    throw DlnaControlException(400, "Renewal cannot replace callback")
                }
                subscriptions.renew(sid, request.headers["timeout"])
            } else {
                if (!request.headers["nt"].equals("upnp:event", ignoreCase = true)) {
                    throw DlnaControlException(412, "Missing event NT")
                }
                subscriptions.subscribe(
                    service,
                    request.headers["callback"].orEmpty(),
                    request.headers["timeout"],
                )
            }
            writeResponse(
                output,
                200,
                "OK",
                "text/plain",
                "",
                extraHeaders = mapOf(
                    "SID" to subscription.sid,
                    "TIMEOUT" to subscriptions.timeoutHeader(subscription),
                ),
            )
            if (sid == null) events.publishInitial(subscription.sid)
        } catch (error: DlnaControlException) {
            writeResponse(output, error.upnpCode, reason(error.upnpCode), "text/plain", "")
        }
    }

    private fun handleUnsubscribe(request: DlnaHttpRequest, output: OutputStream) {
        try {
            val sid = request.headers["sid"] ?: throw DlnaControlException(412, "Missing SID")
            subscriptions.unsubscribe(sid)
            writeResponse(output, 200, "OK", "text/plain", "")
        } catch (error: DlnaControlException) {
            writeResponse(output, 412, "Precondition Failed", "text/plain", "")
        }
    }

    private fun ssdpLoop() {
        val buffer = ByteArray(8192)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                ssdpSocket?.receive(packet) ?: break
                val request = String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1)
                if (request.startsWith("M-SEARCH", ignoreCase = true)) {
                    respondToSearch(request, packet.address, packet.port)
                }
            } catch (error: Throwable) {
                if (running) fail(error)
                break
            }
        }
    }

    private fun respondToSearch(request: String, address: InetAddress, port: Int) {
        val headers = request.lineSequence().drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().uppercase() to
                line.substring(separator + 1).trim()
        }.toMap()
        if (!headers["MAN"].orEmpty().contains("ssdp:discover", ignoreCase = true)) return
        val searchTarget = headers["ST"] ?: return
        val matches = if (searchTarget.equals("ssdp:all", ignoreCase = true)) {
            searchTargets()
        } else {
            searchTargets().filter { it.first.equals(searchTarget, ignoreCase = true) }
        }
        matches.forEach { (target, usn) ->
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$CACHE_SECONDS\r\n")
                append("DATE: ${httpDate()}\r\n")
                append("EXT:\r\n")
                append("LOCATION: ${descriptionUrl}\r\n")
                append("SERVER: $SERVER_HEADER\r\n")
                append("ST: $target\r\n")
                append("USN: $usn\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            runCatching {
                ssdpSocket?.send(DatagramPacket(response, response.size, address, port))
            }
        }
    }

    private fun sendAliveSafely() {
        if (running) runCatching(::sendAlive).onFailure(::fail)
    }

    private fun sendAlive() {
        repeat(2) { sendNotify("ssdp:alive") }
    }

    private fun sendNotify(subtype: String) {
        val socket = ssdpSocket ?: return
        searchTargets().forEach { (target, usn) ->
            val message = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: ${SSDP_GROUP.hostAddress}:$SSDP_PORT\r\n")
                if (subtype == "ssdp:alive") {
                    append("CACHE-CONTROL: max-age=$CACHE_SECONDS\r\n")
                    append("LOCATION: ${descriptionUrl}\r\n")
                    append("SERVER: $SERVER_HEADER\r\n")
                    append("BOOTID.UPNP.ORG: 1\r\n")
                    append("CONFIGID.UPNP.ORG: 1\r\n")
                }
                append("NT: $target\r\n")
                append("NTS: $subtype\r\n")
                append("USN: $usn\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            socket.send(DatagramPacket(message, message.size, SSDP_GROUP, SSDP_PORT))
        }
    }

    private fun searchTargets(): List<Pair<String, String>> {
        val root = "uuid:$uuid"
        return buildList {
            add("upnp:rootdevice" to "$root::upnp:rootdevice")
            add(root to root)
            add(DEVICE_TYPE to "$root::$DEVICE_TYPE")
            DlnaService.entries.forEach { service ->
                add(service.serviceType to "$root::${service.serviceType}")
            }
        }
    }

    private fun baseUrl(): String = localAddress?.hostAddress
        ?.let { "http://$it:${DlnaDescriptions.HTTP_PORT}/" }
        ?: error("DLNA has no local address")

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: String,
        headOnly: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("DATE: ${httpDate()}\r\n")
            append("SERVER: $SERVER_HEADER\r\n")
            append("CONTENT-TYPE: $contentType\r\n")
            append("CONTENT-LENGTH: ${bytes.size}\r\n")
            append("EXT:\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("CONNECTION: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(headers)
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    @Synchronized
    private fun closeResources(sendByeBye: Boolean) {
        if (sendByeBye) runCatching { sendNotify("ssdp:byebye") }
        running = false
        scheduler.shutdownNow()
        runCatching {
            networkInterface?.let { ssdpSocket?.leaveGroup(InetSocketAddress(SSDP_GROUP, SSDP_PORT), it) }
        }
        runCatching { ssdpSocket?.close() }
        runCatching { httpServer?.close() }
        ssdpSocket = null
        httpServer = null
        events.close()
        subscriptions.clear()
        clients.shutdownNow()
        multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        multicastLock = null
        localAddress = null
        networkInterface = null
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "DLNA failure", error)
        onFailure(error)
    }

    private fun httpDate(): String = SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Invalid Action"
        404 -> "Not Found"
        412 -> "Precondition Failed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        else -> "Error"
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        private const val SSDP_PORT = 1900
        private const val CACHE_SECONDS = 1800
        private const val HTTP_TIMEOUT_MS = 15_000
        private const val HTTP_BACKLOG = 24
        private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""
        private const val SERVER_HEADER = "Android/9 UPnP/1.0 03Cast/1.0"
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private val SSDP_GROUP: InetAddress = InetAddress.getByName("239.255.255.250")

        fun stableUuid(seed: ByteArray): String =
            UUID.nameUUIDFromBytes("03cast:".toByteArray() + seed).toString()
    }
}
