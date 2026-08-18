package com.tcrrry.desktopcast.dlna

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class DlnaSoapAction(
    val name: String,
    val arguments: Map<String, String>,
)

object DlnaXml {
    fun parseSoapAction(xml: String): DlnaSoapAction {
        val document = try {
            parse(xml)
        } catch (error: Exception) {
            throw DlnaControlException(402, "Invalid XML")
        }
        val bodies = document.getElementsByTagNameNS("*", "Body")
        val body = bodies.item(0) as? Element
            ?: throw DlnaControlException(402, "Missing SOAP body")
        val action = body.childElements().firstOrNull()
            ?: throw DlnaControlException(401, "Missing SOAP action")
        val arguments = action.childElements().associate { child ->
            (child.localName ?: child.tagName.substringAfter(':')) to child.textContent.trim()
        }
        return DlnaSoapAction(action.localName ?: action.tagName.substringAfter(':'), arguments)
    }

    fun parseMedia(uri: String, metadata: String): DlnaMedia {
        if (metadata.isBlank()) return inferredMedia(uri)
        return runCatching {
            val document = parse(metadata)
            val title = document.firstText("title")
            val creator = document.firstText("creator")
            val mediaClass = document.firstText("class")
            val resource = document.getElementsByTagNameNS("*", "res").item(0) as? Element
            val metadataUri = resource?.textContent?.trim().orEmpty()
            val resolvedUri = metadataUri.takeIf(::isRemoteMediaUri) ?: uri
            val protocolInfo = resource?.getAttribute("protocolInfo").orEmpty()
            val mime = protocolInfo.split(':').getOrNull(2).orEmpty()
            DlnaMedia(
                uri = resolvedUri,
                metadata = metadata,
                title = title,
                creator = creator,
                mimeType = mime,
                kind = inferKind(mime, mediaClass, resolvedUri),
            )
        }.getOrElse { inferredMedia(uri, metadata) }
    }

    fun envelope(service: DlnaService, action: String, arguments: String = ""): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:${action}Response xmlns:u="${service.serviceType}">$arguments</u:${action}Response></s:Body>
</s:Envelope>"""

    fun fault(code: Int, description: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${escape(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body>
</s:Envelope>"""

    fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                },
            )
        }
    }

    fun parseTime(value: String): Long {
        val parts = value.trim().split(':')
        if (parts.size != 3) throw DlnaControlException(402, "Invalid seek target")
        val hours = parts[0].toLongOrNull() ?: throw DlnaControlException(402, "Invalid seek target")
        val minutes = parts[1].toLongOrNull() ?: throw DlnaControlException(402, "Invalid seek target")
        val seconds = parts[2].toDoubleOrNull() ?: throw DlnaControlException(402, "Invalid seek target")
        if (hours < 0 || minutes !in 0..59 || seconds < 0 || seconds >= 60) {
            throw DlnaControlException(402, "Invalid seek target")
        }
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun inferredMedia(uri: String, metadata: String = ""): DlnaMedia {
        val extension = runCatching { URI(uri).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        val mime = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "aac", "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> ""
        }
        return DlnaMedia(
            uri = uri,
            metadata = metadata,
            mimeType = mime,
            kind = inferKind(mime, "", uri),
        )
    }

    private fun inferKind(mime: String, mediaClass: String, uri: String): DlnaMediaKind {
        val normalizedMime = mime.lowercase()
        val normalizedClass = mediaClass.lowercase()
        return when {
            normalizedMime.startsWith("image/") || "imageitem" in normalizedClass -> DlnaMediaKind.IMAGE
            normalizedMime.startsWith("audio/") || "audioitem" in normalizedClass -> DlnaMediaKind.AUDIO
            normalizedMime.startsWith("video/") ||
                normalizedMime.contains("mpegurl") ||
                normalizedMime.contains("dash+xml") ||
                "videoitem" in normalizedClass -> DlnaMediaKind.VIDEO
            uri.substringBefore('?').substringAfterLast('.', "").lowercase() in
                setOf("jpg", "jpeg", "png", "gif", "webp") -> DlnaMediaKind.IMAGE
            else -> DlnaMediaKind.UNKNOWN
        }
    }

    private fun isRemoteMediaUri(value: String): Boolean = runCatching {
        URI(value).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun parse(xml: String): org.w3c.dom.Document {
        require(!FORBIDDEN_DECLARATION.containsMatchIn(xml)) { "DTD declarations are not allowed" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        factory.setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun Element.childElements(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }

    private fun org.w3c.dom.Document.firstText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim().orEmpty()

    private val FORBIDDEN_DECLARATION = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
}
