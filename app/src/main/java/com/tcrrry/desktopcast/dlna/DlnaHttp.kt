package com.tcrrry.desktopcast.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class DlnaHttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun bodyUtf8(): String = body.toString(StandardCharsets.UTF_8)
}

class DlnaHttpException(
    val status: Int,
    message: String,
) : Exception(message)

object DlnaHttpParser {
    const val MAX_HEADER_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    fun read(input: InputStream): DlnaHttpRequest {
        val headerBytes = readHeaders(input)
        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestParts = lines.firstOrNull()?.trim()?.split(' ', limit = 3).orEmpty()
        if (requestParts.size != 3) throw DlnaHttpException(400, "Malformed request line")

        val headers = linkedMapOf<String, String>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) throw DlnaHttpException(400, "Malformed header")
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }

        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw DlnaHttpException(501, "Chunked requests are not supported")
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw DlnaHttpException(413, "Request body is too large")
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw DlnaHttpException(400, "Truncated request body")
            offset += count
        }
        return DlnaHttpRequest(
            method = requestParts[0].uppercase(),
            target = requestParts[1],
            version = requestParts[2],
            headers = headers,
            body = body,
        )
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw DlnaHttpException(400, "Request ended before headers")
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> return output.toByteArray()
                value == '\r'.code -> 1
                else -> 0
            }
        }
        throw DlnaHttpException(431, "Request headers are too large")
    }
}
