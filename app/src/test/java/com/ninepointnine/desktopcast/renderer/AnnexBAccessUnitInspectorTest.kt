package com.ninepointnine.desktopcast.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBAccessUnitInspectorTest {

    @Test
    fun recognizesCompleteH264BootstrapWithFourByteStartCodes() {
        val info = AnnexBAccessUnitInspector.inspect(
            bytes(
                0, 0, 0, 1, 0x67, 1,
                0, 0, 0, 1, 0x68, 2,
                0, 0, 0, 1, 0x65, 3,
            ),
            isH265 = false,
        )

        assertTrue(info.hasCodecConfiguration)
        assertTrue(info.isRandomAccess)
    }

    @Test
    fun recognizesThreeByteH264IdrWithoutTreatingItAsConfiguration() {
        val info = AnnexBAccessUnitInspector.inspect(
            bytes(0, 0, 1, 0x65, 1, 2, 3),
            isH265 = false,
        )

        assertTrue(info.isRandomAccess)
        assertFalse(info.hasCodecConfiguration)
    }

    @Test
    fun keepsH264ConfigurationDistinctFromRandomAccess() {
        val info = AnnexBAccessUnitInspector.inspect(
            bytes(
                0, 0, 1, 0x67, 1,
                0, 0, 1, 0x68, 2,
            ),
            isH265 = false,
        )

        assertTrue(info.hasCodecConfiguration)
        assertFalse(info.isRandomAccess)
    }

    @Test
    fun recognizesCompleteH265Bootstrap() {
        val info = AnnexBAccessUnitInspector.inspect(
            bytes(
                0, 0, 0, 1, 32 shl 1, 1,
                0, 0, 0, 1, 33 shl 1, 1,
                0, 0, 0, 1, 34 shl 1, 1,
                0, 0, 0, 1, 19 shl 1, 1,
            ),
            isH265 = true,
        )

        assertTrue(info.hasCodecConfiguration)
        assertTrue(info.isRandomAccess)
    }

    @Test
    fun ordinaryInterFrameIsNotAStartupPoint() {
        val h264 = AnnexBAccessUnitInspector.inspect(
            bytes(0, 0, 0, 1, 0x41, 1),
            isH265 = false,
        )
        val h265 = AnnexBAccessUnitInspector.inspect(
            bytes(0, 0, 0, 1, 1 shl 1, 1),
            isH265 = true,
        )

        assertFalse(h264.isRandomAccess)
        assertFalse(h264.hasCodecConfiguration)
        assertFalse(h265.isRandomAccess)
        assertFalse(h265.hasCodecConfiguration)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
