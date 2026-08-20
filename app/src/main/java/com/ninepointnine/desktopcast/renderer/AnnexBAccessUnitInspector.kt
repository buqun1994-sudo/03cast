package com.ninepointnine.desktopcast.renderer

/** Identifies decoder bootstrap data in H.264/H.265 Annex-B access units. */
internal object AnnexBAccessUnitInspector {

    fun inspect(data: ByteArray, isH265: Boolean): AnnexBAccessUnitInfo {
        if (data.size < 4) return AnnexBAccessUnitInfo(false, false)
        var randomAccess = false
        var hasVps = false
        var hasSps = false
        var hasPps = false
        var index = 0
        while (index <= data.size - 4) {
            val startCodeBytes = when {
                index <= data.size - 5 &&
                    data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                    data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
                data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                    data[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeBytes == 0) {
                index++
                continue
            }
            val headerIndex = index + startCodeBytes
            if (headerIndex >= data.size) break
            val type = if (isH265) {
                (data[headerIndex].toInt() ushr 1) and 0x3f
            } else {
                data[headerIndex].toInt() and 0x1f
            }
            if (isH265) {
                randomAccess = randomAccess || type in 16..21
                hasVps = hasVps || type == 32
                hasSps = hasSps || type == 33
                hasPps = hasPps || type == 34
            } else {
                randomAccess = randomAccess || type == 5
                hasSps = hasSps || type == 7
                hasPps = hasPps || type == 8
            }
            index = headerIndex + 1
        }
        return AnnexBAccessUnitInfo(
            isRandomAccess = randomAccess,
            hasCodecConfiguration = hasSps && hasPps && (!isH265 || hasVps),
        )
    }
}

internal data class AnnexBAccessUnitInfo(
    val isRandomAccess: Boolean,
    val hasCodecConfiguration: Boolean,
)
