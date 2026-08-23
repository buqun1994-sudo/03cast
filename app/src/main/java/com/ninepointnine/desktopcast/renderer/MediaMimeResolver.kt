package com.ninepointnine.desktopcast.renderer

import androidx.media3.common.MimeTypes
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Resolves only high-confidence media types; null keeps Media3's normal probing path. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object MediaMimeResolver {

    fun resolve(location: String, declaredMimeType: String? = null): String? {
        val uriMime = inferFromLocation(location)
        // An explicit HLS marker in the URI wins over a sender's overly broad
        // video/* declaration, which is common for application-generated DLNA URLs.
        if (uriMime == MimeTypes.APPLICATION_M3U8) return uriMime

        return normalizeDeclared(declaredMimeType) ?: uriMime
    }

    private fun normalizeDeclared(value: String?): String? {
        val mime = value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return when (mime) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            -> MimeTypes.APPLICATION_M3U8

            "video/mp2t" -> MimeTypes.VIDEO_MP2T
            "video/mp4" -> MimeTypes.VIDEO_MP4
            "video/quicktime" -> MimeTypes.VIDEO_QUICK_TIME
            "video/x-matroska" -> MimeTypes.VIDEO_MATROSKA
            "video/webm" -> MimeTypes.VIDEO_WEBM
            "video/mpeg" -> MimeTypes.VIDEO_MPEG
            "video/x-msvideo" -> MimeTypes.VIDEO_AVI
            "audio/mpeg" -> MimeTypes.AUDIO_MPEG
            "audio/mp4" -> MimeTypes.AUDIO_MP4
            "audio/aac" -> MimeTypes.AUDIO_AAC
            "audio/flac" -> MimeTypes.AUDIO_FLAC
            "audio/wav", "audio/x-wav" -> MimeTypes.AUDIO_WAV
            "image/jpeg" -> MimeTypes.IMAGE_JPEG
            "image/png" -> MimeTypes.IMAGE_PNG
            "image/gif" -> "image/gif"
            "image/webp" -> MimeTypes.IMAGE_WEBP
            else -> null
        }
    }

    private fun inferFromLocation(location: String): String? {
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase()
        val query = decode(uri.rawQuery.orEmpty()).lowercase()
        val searchable = "$path?$query"

        if (hasHlsMarker(searchable)) return MimeTypes.APPLICATION_M3U8

        return when (path.substringAfterLast('.', "")) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mov" -> MimeTypes.VIDEO_QUICK_TIME
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "mpeg", "mpg" -> MimeTypes.VIDEO_MPEG
            "ts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "aac" -> MimeTypes.AUDIO_MP4
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "jpg", "jpeg" -> MimeTypes.IMAGE_JPEG
            "png" -> MimeTypes.IMAGE_PNG
            "gif" -> "image/gif"
            "webp" -> MimeTypes.IMAGE_WEBP
            else -> null
        }
    }

    private fun hasHlsMarker(value: String): Boolean =
        value.contains(".m3u8") ||
            value.contains("m3u8=") ||
            value.contains("format=m3u8") ||
            value.contains("type=m3u8") ||
            value.contains("mime=application/vnd.apple.mpegurl") ||
            value.contains("mime=application/x-mpegurl") ||
            value.contains("hls=true") ||
            value.contains("/hls/")

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
