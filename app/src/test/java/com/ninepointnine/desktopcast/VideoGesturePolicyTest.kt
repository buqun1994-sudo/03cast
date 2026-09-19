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

    @Test
    fun receiverNextUsesProvidedItemOrSharedUiSeekPath() {
        val appDirectory = findAppDirectory()
        val router = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/service/CastPlaybackRouter.kt",
        ).readText()
        val player = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/renderer/NetworkMediaPlayer.kt",
        ).readText()
        val airPlayAdapter = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/service/AirPlayPlaybackAdapter.kt",
        ).readText()
        val dlnaAdapter = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/service/DlnaPlaybackAdapter.kt",
        ).readText()
        val activity = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/MainActivity.kt",
        ).readText()
        val nextEntry = router
            .substringAfter("fun advanceToNextVideo(): Boolean")
            .substringBefore("private fun seekFromUi(positionMs: Long): Boolean")
        val sharedSeekEntry = router
            .substringAfter("private fun seekFromUi(positionMs: Long): Boolean")
            .substringBefore("fun dlnaSnapshot()")
        val swipeEntry = activity
            .substringAfter("GestureMode.VERTICAL ->")
            .substringBefore("GestureMode.UNDECIDED ->")
        val buttonEntry = activity
            .substringAfter("mediaNextControl.setOnClickListener")
            .substringBefore("(mediaProgressView as DefaultTimeBar)")

        assertTrue(nextEntry.contains("NextVideoPolicy.resolve"))
        assertTrue(nextEntry.contains("NextVideoAction.SelectProvidedItem"))
        assertTrue(nextEntry.contains("networkQueue.next()"))
        assertTrue(nextEntry.contains("strategy=provided-next"))
        assertTrue(nextEntry.contains("is NextVideoAction.SeekNearEnd"))
        assertTrue(nextEntry.contains("seekFromUi(action.positionMs)"))
        assertTrue(nextEntry.contains("strategy=near-end-seek"))
        assertTrue(nextEntry.contains("NextVideoAction.Unavailable -> false"))
        assertTrue(sharedSeekEntry.contains("dlnaAdapter.seekFromUi(targetPositionMs)"))
        assertTrue(sharedSeekEntry.contains("airPlayAdapter.seek(targetPositionMs)"))
        assertFalse(sharedSeekEntry.contains("state.content != CastContentKind.NETWORK_VIDEO"))
        assertTrue(
            router.substringAfter("fun seekToPosition(positionMs: Long)")
                .substringBefore("fun setSeekPreview(positionMs: Long?)")
                .contains("seekFromUi(positionMs)"),
        )
        assertTrue(swipeEntry.contains("castService?.advanceToNextVideo()"))
        assertTrue(buttonEntry.contains("castService?.advanceToNextVideo()"))
        assertTrue(activity.contains("NextVideoPolicy.canAdvance"))
        assertFalse(activity.contains("queue?.next != null || (state.canSeek && state.durationMs > 0L)"))
        assertFalse(player.contains("finishCurrentAtEnd"))
        assertFalse(nextEntry.contains("requestNextVideo"))
        assertFalse(nextEntry.contains("projectNaturalEndForSender"))
        assertFalse(nextEntry.contains("playWhenReady"))
        assertFalse(nextEntry.contains("postDelayed"))
        assertFalse(router.contains("REMOTE_NEXT_CONFIRMATION_MS"))
        assertFalse(airPlayAdapter.contains("fun requestNextVideo"))
        assertFalse(airPlayAdapter.contains("senderAdvance"))
        assertFalse(dlnaAdapter.contains("senderAdvance"))
        assertFalse(dlnaAdapter.contains("deferStop"))
        assertFalse(airPlayAdapter.contains("deferStop"))
        assertFalse(dlnaAdapter.contains("STOP_HANDOFF_TIMEOUT_MS"))
        assertFalse(airPlayAdapter.contains("STOP_HANDOFF_TIMEOUT_MS"))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
