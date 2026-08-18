package com.tcrrry.desktopcast.dlna

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaHttpParserTest {
    @Test
    fun readsUtf8BodyByBytes() {
        val body = "<title>中文影片</title>".toByteArray(StandardCharsets.UTF_8)
        val request = (
            "POST /control/AVTransport HTTP/1.1\r\n" +
                "Content-Type: text/xml\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1) + body

        val parsed = DlnaHttpParser.read(ByteArrayInputStream(request))

        assertEquals("POST", parsed.method)
        assertEquals("/control/AVTransport", parsed.target)
        assertEquals("<title>中文影片</title>", parsed.bodyUtf8())
    }

    @Test(expected = DlnaHttpException::class)
    fun rejectsTruncatedBody() {
        val request = "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"
        DlnaHttpParser.read(ByteArrayInputStream(request.toByteArray()))
    }
}
