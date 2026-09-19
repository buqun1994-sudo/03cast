package com.ninepointnine.desktopcast

import kotlin.math.abs
import kotlin.math.roundToLong

internal enum class VideoGestureAxis {
    HORIZONTAL,
    VERTICAL,
}

/** Pure gesture thresholds and playback-position mapping shared by the playback UI. */
internal object VideoGesturePolicy {
    const val AXIS_DOMINANCE_RATIO = 1.3f

    fun lockAxis(deltaX: Float, deltaY: Float, touchSlopPx: Float): VideoGestureAxis? {
        val horizontalDistance = abs(deltaX)
        val verticalDistance = abs(deltaY)
        if (maxOf(horizontalDistance, verticalDistance) < touchSlopPx) return null
        return when {
            horizontalDistance > verticalDistance * AXIS_DOMINANCE_RATIO ->
                VideoGestureAxis.HORIZONTAL
            verticalDistance > horizontalDistance * AXIS_DOMINANCE_RATIO ->
                VideoGestureAxis.VERTICAL
            else -> null
        }
    }

    fun verticalTriggerDistancePx(viewHeightPx: Int, density: Float): Float =
        maxOf(120f * density, viewHeightPx * 0.18f)

    fun isUpSwipe(deltaY: Float, viewHeightPx: Int, density: Float): Boolean =
        deltaY <= -verticalTriggerDistancePx(viewHeightPx, density)

    fun previewPositionMs(
        startPositionMs: Long,
        deltaXPx: Float,
        viewWidthPx: Int,
        durationMs: Long,
    ): Long {
        if (viewWidthPx <= 0 || durationMs <= 0L) return startPositionMs.coerceAtLeast(0L)
        val deltaMs = deltaXPx.toDouble() / viewWidthPx.toDouble() * durationMs.toDouble()
        return (startPositionMs.toDouble() + deltaMs)
            .roundToLong()
            .coerceIn(0L, durationMs)
    }

}
