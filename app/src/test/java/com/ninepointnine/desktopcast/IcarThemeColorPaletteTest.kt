package com.ninepointnine.desktopcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class IcarThemeColorPaletteTest {

    @Test
    fun `purple theme key resolves to the verified iCAR primary color`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 32, nightMode = false)

        assertEquals(0xFF5C66BF.toInt(), palette.accentColor)
        assertEquals(0xFFFFFFFF.toInt(), palette.accentTextColor)
    }

    @Test
    fun `runtime pink theme key resolves to the verified pink primary color`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 33, nightMode = true)

        assertEquals(0xFFDE5185.toInt(), palette.accentColor)
        assertEquals(0xFF16161B.toInt(), palette.accentTextColor)
    }

    @Test
    fun `yellow theme uses dark selected text for readability`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 64, nightMode = false)

        assertEquals(0xFFFDFD54.toInt(), palette.accentColor)
        assertEquals(0xFF16161B.toInt(), palette.accentTextColor)
        assertTrue(contrastRatio(palette.accentSurfaceColor, 0xFFFFFFFF.toInt()) >= 4.5)
        assertTrue(palette.accentSurfaceColor != palette.accentColor)
    }

    @Test
    fun `unknown theme key falls back to the default vehicle blue`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 999, nightMode = true)

        assertEquals(0xFF1A8CFF.toInt(), palette.accentColor)
    }

    @Test
    fun `day and night palettes use distinct shades for theme keys with variants`() {
        val day = IcarThemeColorPalette.resolve(themeKey = 2, nightMode = false)
        val night = IcarThemeColorPalette.resolve(themeKey = 2, nightMode = true)

        assertEquals(0xFF92B5CD.toInt(), day.accentColor)
        assertEquals(0xFFB5D3E2.toInt(), night.accentColor)
    }

    @Test
    fun `default blue theme uses dark text on its filled accent`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = null, nightMode = false)

        assertEquals(0xFF16161B.toInt(), palette.accentTextColor)
    }

    @Test
    fun `metal theme falls back to pure black when both branded text choices miss contrast`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 4, nightMode = false)

        assertEquals(0xFF000000.toInt(), palette.accentTextColor)
    }

    @Test
    fun `night surface accent remains readable for a light cyan theme`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 2, nightMode = true)

        assertTrue(contrastRatio(palette.accentSurfaceColor, 0xFF0A0B0C.toInt()) >= 4.5)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val normalized = value / 255.0
                return if (normalized <= 0.03928) normalized / 12.92
                else ((normalized + 0.055) / 1.055).pow(2.4)
            }
            return channel((color shr 16) and 0xff) * 0.2126 +
                channel((color shr 8) and 0xff) * 0.7152 +
                channel(color and 0xff) * 0.0722
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
