package com.ninepointnine.desktopcast

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Generates the build-configured agreement QR code without a network request. */
object TermsQrCodeFactory {
    fun create(url: String, sizePx: Int): Bitmap {
        require(url.startsWith("https://")) { "Terms URL must use HTTPS" }
        val size = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val matrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to QUIET_ZONE_MODULES),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private const val MIN_SIZE_PX = 256
    private const val QUIET_ZONE_MODULES = 1
}
