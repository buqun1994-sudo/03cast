package com.ninepointnine.desktopcast

import kotlin.math.pow

/** Runtime accent colors supplied by the public iCAR theme setting. */
internal data class IcarThemePalette(
    val accentColor: Int,
    val accentTextColor: Int,
    val accentSurfaceColor: Int,
)

/**
 * Maps the iCAR theme key to the verified primary shade used by the vehicle
 * settings. The system uiMode remains the only source of light/dark surfaces;
 * this key is used only for the accent color.
 */
internal object IcarThemeColorPalette {
    const val GLOBAL_THEME_KEY = "com.mb.provider.theme_key"

    private const val CYAN = 2
    private const val METAL = 4
    private const val ORANGE = 8
    private const val PINK_LEGACY = 16
    private const val PURPLE = 32
    private const val PINK = 33
    private const val YELLOW = 64

    fun resolve(themeKey: Int?, nightMode: Boolean): IcarThemePalette {
        val accentColor = when (themeKey) {
            CYAN -> if (nightMode) CYAN_NIGHT else CYAN_DAY
            METAL -> if (nightMode) METAL_NIGHT else METAL_DAY
            ORANGE -> if (nightMode) ORANGE_NIGHT else ORANGE_DAY
            PINK_LEGACY, PINK -> if (nightMode) PINK_NIGHT else PINK_DAY
            PURPLE -> PURPLE_PRIMARY
            YELLOW -> if (nightMode) YELLOW_NIGHT else YELLOW_DAY
            else -> DEFAULT_PRIMARY
        }
        return IcarThemePalette(
            accentColor = accentColor,
            accentTextColor = readableAccentText(accentColor),
            accentSurfaceColor = readableSurfaceAccent(accentColor, nightMode),
        )
    }

    private fun readableAccentText(accentColor: Int): Int {
        val darkContrast = contrastRatio(BLACK, accentColor)
        val lightContrast = contrastRatio(WHITE, accentColor)
        return when {
            darkContrast >= MIN_ACCENT_CONTRAST -> BLACK
            lightContrast >= MIN_ACCENT_CONTRAST -> WHITE
            contrastRatio(PURE_BLACK, accentColor) >= lightContrast -> PURE_BLACK
            else -> WHITE
        }
    }

    /**
     * Keeps the vehicle hue while ensuring links and small icons remain readable
     * on the day/night application surfaces. Filled controls use accentColor.
     */
    private fun readableSurfaceAccent(color: Int, nightMode: Boolean): Int {
        val surface = if (nightMode) NIGHT_SURFACE else WHITE
        if (contrastRatio(color, surface) >= MIN_SURFACE_CONTRAST) return color
        val target = if (nightMode) WHITE else BLACK
        var low = 0f
        var high = 1f
        repeat(24) {
            val factor = (low + high) / 2f
            val candidate = blend(color, target, factor)
            if (contrastRatio(candidate, surface) >= MIN_SURFACE_CONTRAST) {
                high = factor
            } else {
                low = factor
            }
        }
        return blend(color, target, high)
    }

    private fun blend(source: Int, target: Int, factor: Float): Int {
        fun channel(shift: Int): Int {
            val from = (source shr shift) and 0xff
            val to = (target shr shift) and 0xff
            return (from + (to - from) * factor).toInt().coerceIn(0, 255)
        }
        return 0xff000000.toInt() or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val normalized = channel / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        val red = linear((color shr 16) and 0xff)
        val green = linear((color shr 8) and 0xff)
        val blue = linear(color and 0xff)
        return red * 0.2126 + green * 0.7152 + blue * 0.0722
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF16161B.toInt()
    private const val PURE_BLACK = 0xFF000000.toInt()
    private const val NIGHT_SURFACE = 0xFF0A0B0C.toInt()
    private const val MIN_ACCENT_CONTRAST = 4.5
    private const val MIN_SURFACE_CONTRAST = 4.5
    private const val DEFAULT_PRIMARY = 0xFF1A8CFF.toInt()
    private const val CYAN_DAY = 0xFF92B5CD.toInt()
    private const val CYAN_NIGHT = 0xFFB5D3E2.toInt()
    private const val METAL_DAY = 0xFF9F704B.toInt()
    private const val METAL_NIGHT = 0xFFB68A61.toInt()
    private const val ORANGE_DAY = 0xFFFAC813.toInt()
    private const val ORANGE_NIGHT = 0xFFE6B609.toInt()
    private const val PINK_DAY = 0xFFFB86A9.toInt()
    private const val PINK_NIGHT = 0xFFDE5185.toInt()
    private const val PURPLE_PRIMARY = 0xFF5C66BF.toInt()
    private const val YELLOW_DAY = 0xFFFDFD54.toInt()
    private const val YELLOW_NIGHT = 0xFFFDF200.toInt()
}
