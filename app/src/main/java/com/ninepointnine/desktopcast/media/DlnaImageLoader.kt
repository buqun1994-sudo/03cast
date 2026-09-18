package com.ninepointnine.desktopcast.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.ninepointnine.desktopcast.network.PhysicalNetworkPolicy
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DlnaImageLoader(
    private val scope: CoroutineScope,
    private val physicalNetworkProvider: () -> Network? = { null },
    context: Context? = null,
) {
    private val connectivity = context?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var job: Job? = null

    fun load(
        uri: String,
        onLoaded: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        cancel()
        job = scope.launch {
            runCatching { downloadAndDecode(uri) }
                .onSuccess(onLoaded)
                .onFailure(onError)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun downloadAndDecode(uri: String): Bitmap = withContext(Dispatchers.IO) {
        val url = URL(uri)
        require(url.protocol == "http" || url.protocol == "https") { "Unsupported image URL" }
        val selectedNetwork = physicalNetworkProvider()
        val rawConnection: URLConnection = if (selectedNetwork != null) {
            Log.i(TAG, "Opening image with local-network preference: $selectedNetwork")
            PhysicalNetworkPolicy.openConnection(
                url,
                selectedNetwork,
                linkPropertiesProvider = { network -> connectivity?.getLinkProperties(network) },
            ) ?: url.openConnection()
        } else url.openConnection()
        val connection = (rawConnection as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "03Cast/1.0 DLNA/1.5")
        }
        try {
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= MAX_IMAGE_BYTES) { "Image is too large" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_IMAGE_BYTES) error("Image is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_WIDTH * 2 ||
                bounds.outHeight / sampleSize > MAX_HEIGHT * 2
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: error("Image decoder rejected content")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "DlnaImageLoader"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
    }
}
