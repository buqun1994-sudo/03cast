package com.ninepointnine.desktopcast.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CastWindowPolicyTest {
    @Test
    fun standardWindowCreatesDisposableFullscreenTask() {
        val transition = CastWindowPolicy.transitionFrom(CastWindowMode.STANDARD)

        assertEquals(CastWindowMode.FULLSCREEN, transition.target)
        assertFalse(transition.reuseTargetTask)
        assertFalse(transition.retireSourceAfterTargetReady)
    }

    @Test
    fun fullscreenReturnsToExistingStandardTaskThenClosesItself() {
        val transition = CastWindowPolicy.transitionFrom(CastWindowMode.FULLSCREEN)

        assertEquals(CastWindowMode.STANDARD, transition.target)
        assertTrue(transition.reuseTargetTask)
        assertTrue(transition.retireSourceAfterTargetReady)
    }

    @Test
    fun targetLaunchFailureDoesNotRetireSource() {
        var targetFailureReported = false

        val launched = executeWindowTransition(
            launchTarget = { throw IllegalStateException("launch rejected") },
            onTargetLaunchFailure = { targetFailureReported = true },
        )

        assertFalse(launched)
        assertTrue(targetFailureReported)
    }

    @Test
    fun acceptedTargetDoesNotRetireSourceUntilReady() {
        val events = mutableListOf<String>()
        var targetFailureReported = false
        var retirementFailureReported = false

        val launched = executeWindowTransition(
            launchTarget = { events += "launch" },
            onTargetLaunchFailure = { targetFailureReported = true },
        )

        assertTrue(launched)
        assertEquals(listOf("launch"), events)

        executeSourceRetirement(
            retireSource = {
                events += "retire"
                throw IllegalStateException("retirement rejected")
            },
            onFailure = { retirementFailureReported = true },
        )

        assertEquals(listOf("launch", "retire"), events)
        assertFalse(targetFailureReported)
        assertTrue(retirementFailureReported)
    }

    @Test
    fun networkSurfaceResizeDoesNotRebindTheSameHolder() {
        val activitySource = File(
            findAppDirectory(),
            "src/main/java/com/ninepointnine/desktopcast/MainActivity.kt",
        ).readText()
        val networkSurfaceCallback = activitySource
            .substringAfter("binding.mediaSurface.holder.addCallback")
            .substringBefore("private fun attachSurfaces")
        val resizeCallback = networkSurfaceCallback
            .substringAfter("override fun surfaceChanged")
            .substringBefore("override fun surfaceDestroyed")
        val playerSource = File(
            findAppDirectory(),
            "src/main/java/com/ninepointnine/desktopcast/renderer/NetworkMediaPlayer.kt",
        ).readText()

        assertFalse(resizeCallback.contains("setMediaSurface"))
        assertTrue(playerSource.contains("if (pendingSurfaceHolder === holder) return@runOnMain"))
    }

    @Test
    fun mediaHandoffWaitsForRendererDetachAndNeverHotSwapsMirrorCodec() {
        val appDirectory = findAppDirectory()
        val playerSource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/renderer/NetworkMediaPlayer.kt",
        ).readText()
        val mirrorSource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/renderer/VideoRenderer.kt",
        ).readText()
        val routerSource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/service/CastPlaybackRouter.kt",
        ).readText()

        assertTrue(playerSource.contains("Renderer.MSG_SET_VIDEO_OUTPUT"))
        assertTrue(playerSource.contains("blockUntilDelivered(SURFACE_HANDOFF_TIMEOUT_MS)"))
        assertTrue(playerSource.contains("awaitOutputSurface"))
        assertFalse(mirrorSource.contains("setOutputSurface(surface)"))
        assertTrue(routerSource.contains("prepareWindowHandoff()"))
        assertTrue(routerSource.contains("confirmWindowHandoffOutput"))
    }

    @Test
    fun targetCompletionRequiresRendererAckInAdditionToSurfaceValidity() {
        val appDirectory = findAppDirectory()
        val activitySource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/MainActivity.kt",
        ).readText()
        val serviceSource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/service/CastService.kt",
        ).readText()
        val navigatorSource = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/window/CastWindowNavigator.kt",
        ).readText()

        assertTrue(activitySource.contains("completeHandoffIfRequested(intent, targetHolder)"))
        assertTrue(serviceSource.contains("confirmWindowHandoffOutput(sessionState.value.content, targetHolder)"))
        assertTrue(navigatorSource.contains("targetHolder: SurfaceHolder?"))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
