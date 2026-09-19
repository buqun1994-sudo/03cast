package com.ninepointnine.desktopcast

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGesturePolicyTest {
    @Test
    fun directionLocksOnlyAfterSlopAndClearDominance() {
        assertNull(VideoGesturePolicy.lockAxis(7f, 2f, 8f))
        assertNull(VideoGesturePolicy.lockAxis(20f, 18f, 8f))
        assertEquals(
            VideoGestureAxis.HORIZONTAL,
            VideoGesturePolicy.lockAxis(20f, 10f, 8f),
        )
        assertEquals(
            VideoGestureAxis.VERTICAL,
            VideoGesturePolicy.lockAxis(10f, -20f, 8f),
        )
    }

    @Test
    fun horizontalPreviewMapsOneScreenToDurationAndClamps() {
        assertEquals(45_000L, VideoGesturePolicy.previewPositionMs(30_000L, 250f, 1_000, 60_000L))
        assertEquals(0L, VideoGesturePolicy.previewPositionMs(10_000L, -1_000f, 1_000, 60_000L))
        assertEquals(60_000L, VideoGesturePolicy.previewPositionMs(50_000L, 1_000f, 1_000, 60_000L))
    }

    @Test
    fun upSwipeUsesExistingCarDisplayThresholdAndNeverAcceptsDownwardMotion() {
        assertEquals(216f, VideoGesturePolicy.verticalTriggerDistancePx(1_200, 1f), 0.01f)
        assertTrue(VideoGesturePolicy.isUpSwipe(-217f, 1_200, 1f))
        assertFalse(VideoGesturePolicy.isUpSwipe(216f, 1_200, 1f))
    }

    @Test
    fun currentTimeUsesAppOwnedViewInsteadOfMedia3ManagedPosition() {
        val layout = File(
            findAppDirectory(),
            "src/main/res/layout/cast_media_control_view.xml",
        ).readText()

        assertTrue(layout.contains("android:id=\"@+id/cast_position\""))
        assertFalse(layout.contains("android:id=\"@id/exo_position\""))
        assertFalse(layout.contains("android:id=\"@+id/exo_position\""))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
