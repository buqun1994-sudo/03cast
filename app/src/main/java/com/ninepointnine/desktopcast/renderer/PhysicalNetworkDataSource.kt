package com.ninepointnine.desktopcast.renderer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import com.ninepointnine.desktopcast.network.PhysicalNetworkPolicy
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLConnection

/**
 * Uses the selected physical network only for sender-local media URLs. Public
 * media and redirects that leave the LAN use the system default network.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PhysicalNetworkDataSourceFactory(
    context: Context,
    private val physicalNetworkProvider: () -> Network?,
) : DataSource.Factory {

    private val fallbackFactory = DefaultDataSource.Factory(context)
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun createDataSource(): DataSource = PhysicalNetworkDataSource(
        physicalNetworkProvider = physicalNetworkProvider,
        linkPropertiesProvider = { network -> connectivity?.getLinkProperties(network) },
        fallbackFactory = fallbackFactory,
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class PhysicalNetworkDataSource(
    private val physicalNetworkProvider: () -> Network?,
    private val linkPropertiesProvider: (Network) -> android.net.LinkProperties?,
    private val fallbackFactory: DataSource.Factory,
) : BaseDataSource(/* isNetwork= */ true), HttpDataSource {

    private var dataSpec: DataSpec? = null
    private var connection: HttpURLConnection? = null
    private var fallback: DataSource? = null
    private var inputStream: java.io.InputStream? = null
    private var bytesToRead = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L
    private var opened = false
    private var responseCode = -1
    private var currentUri: android.net.Uri? = null
    private val requestProperties = HttpDataSource.RequestProperties()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        if (!isHttp(dataSpec.uri.scheme)) {
            val source = fallbackFactory.createDataSource()
            fallback = source
            return source.open(dataSpec)
        }

        transferInitializing(dataSpec)
        try {
            val openedConnection = openConnection(dataSpec)
            connection = openedConnection
            responseCode = openedConnection.responseCode
            currentUri = android.net.Uri.parse(openedConnection.url.toString())
            if (responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                throw HttpDataSource.InvalidResponseCodeException(
                    responseCode,
                    openedConnection.responseMessage,
                    null,
                    openedConnection.headerFields,
                    dataSpec,
                    ByteArray(0),
                )
            }

            val requestedPosition = dataSpec.position
            val serverStartedAtZero = responseCode == HTTP_OK
            val contentLength = openedConnection.getHeaderFieldLong("Content-Length", -1L)
            bytesToRead = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                contentLength == -1L -> C.LENGTH_UNSET.toLong()
                serverStartedAtZero -> (contentLength - requestedPosition).coerceAtLeast(0L)
                else -> contentLength
            }
            inputStream = if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_HEAD) {
                null
            } else {
                openedConnection.inputStream
            }
            if (serverStartedAtZero && requestedPosition > 0L) skipFully(requestedPosition)
            opened = true
            transferStarted(dataSpec)
            return bytesToRead
        } catch (error: HttpDataSource.HttpDataSourceException) {
            closeConnectionQuietly()
            throw error
        } catch (error: IOException) {
            closeConnectionQuietly()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                error,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesRead >= bytesToRead) {
            return C.RESULT_END_OF_INPUT
        }
        val requested = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        }
        return try {
            val count = inputStream?.read(buffer, offset, requested) ?: -1
            if (count == -1) C.RESULT_END_OF_INPUT else {
                bytesRead += count
                bytesTransferred(count)
                count
            }
        } catch (error: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                error,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
    }

    override fun getUri(): android.net.Uri? = fallback?.uri ?: currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallback?.responseHeaders ?: connection?.headerFields ?: emptyMap()

    override fun getResponseCode(): Int = responseCode

    override fun setRequestProperty(name: String, value: String) = requestProperties.set(name, value)

    override fun clearRequestProperty(name: String) = requestProperties.remove(name)

    override fun clearAllRequestProperties() = requestProperties.clear()

    override fun close() {
        fallback?.let {
            try {
                it.close()
            } catch (error: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    error,
                    checkNotNull(dataSpec),
                    HttpDataSource.HttpDataSourceException.TYPE_CLOSE,
                )
            } finally {
                fallback = null
            }
            return
        }

        try {
            inputStream?.close()
        } catch (error: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                error,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_CLOSE,
            )
        } finally {
            closeConnectionQuietly()
            if (opened) transferEnded()
            opened = false
            inputStream = null
            dataSpec = null
            bytesRead = 0L
            bytesToRead = C.LENGTH_UNSET.toLong()
        }
    }

    private fun openConnection(dataSpec: DataSpec): HttpURLConnection {
        val selectedNetwork = physicalNetworkProvider()
        Log.i(
            TAG,
            if (selectedNetwork == null) {
                "Opening media with the system default network"
            } else {
                "Opening media with local-network preference: $selectedNetwork"
            },
        )
        return openConnectionOnNetwork(dataSpec, selectedNetwork)
    }

    private fun openConnectionOnNetwork(dataSpec: DataSpec, selectedNetwork: Network?): HttpURLConnection {
        var url = URL(dataSpec.uri.toString())
        var method = dataSpec.httpMethod
        var body = dataSpec.httpBody
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val rawConnection: URLConnection = if (selectedNetwork != null) {
                PhysicalNetworkPolicy.openConnection(url, selectedNetwork, linkPropertiesProvider)
                    ?: url.openConnection()
            } else {
                url.openConnection()
            }
            val http = rawConnection as? HttpURLConnection
                ?: throw ProtocolException("Unsupported media URL: $url")
            configure(http, dataSpec, method, body)
            val code = http.responseCode
            if (code !in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX) return http
            val location = http.getHeaderField("Location")
            if (location.isNullOrBlank() || redirectCount == MAX_REDIRECTS) return http
            http.disconnect()
            val redirectedUrl = URL(url, location)
            if (!isHttp(redirectedUrl.protocol)) {
                throw ProtocolException("Cross-protocol media redirect is not supported: $redirectedUrl")
            }
            if (code == HTTP_SEE_OTHER ||
                ((code == HTTP_MOVED_PERMANENTLY || code == HTTP_FOUND) &&
                    method != DataSpec.HTTP_METHOD_GET)
            ) {
                method = DataSpec.HTTP_METHOD_GET
                body = null
            }
            url = redirectedUrl
        }
        throw ProtocolException("Too many media redirects")
    }

    private fun configure(connection: HttpURLConnection, dataSpec: DataSpec, method: Int, body: ByteArray?) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = DataSpec.getStringForHttpMethod(method)
        connection.doInput = true
        if (body != null) connection.doOutput = true
        val headers = LinkedHashMap<String, String>().apply {
            putAll(requestProperties.snapshot)
            putAll(dataSpec.httpRequestHeaders)
        }
        if (body != null && !headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
            headers["Content-Type"] = "application/octet-stream"
        }
        headers.putIfAbsent("User-Agent", "03Cast/1.0 DLNA/1.5")
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) ""
            else (dataSpec.position + dataSpec.length - 1).toString()
            headers.putIfAbsent("Range", "bytes=${dataSpec.position}-$end")
        }
        headers.putIfAbsent("Accept-Encoding", "identity")
        headers.forEach(connection::setRequestProperty)
        if (body != null) connection.outputStream.use { it.write(body) }
    }

    private fun skipFully(length: Long) {
        var remaining = length
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0) {
            val read = inputStream?.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()) ?: -1
            if (read == -1) throw EOFException("Unable to skip to media position $length")
            remaining -= read
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { inputStream?.close() }
        inputStream = null
        connection?.disconnect()
        connection = null
        responseCode = -1
        currentUri = null
    }

    private fun isHttp(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    private companion object {
        const val TAG = "PhysicalNetworkDataSource"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 20
        const val SKIP_BUFFER_SIZE = 16 * 1024
        const val HTTP_OK = 200
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
        const val HTTP_REDIRECT_MIN = 300
        const val HTTP_REDIRECT_MAX = 399
        const val HTTP_MOVED_PERMANENTLY = 301
        const val HTTP_FOUND = 302
        const val HTTP_SEE_OTHER = 303
    }
}
