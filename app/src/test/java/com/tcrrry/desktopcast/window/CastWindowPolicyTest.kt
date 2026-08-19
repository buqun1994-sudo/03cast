package com.tcrrry.desktopcast.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastWindowPolicyTest {
    @Test
    fun standardWindowKeepsItsTaskWhileOpeningFullscreen() {
        val transition = CastWindowPolicy.transitionFrom(CastWindowMode.STANDARD)

        assertEquals(CastWindowMode.FULLSCREEN, transition.target)
        assertTrue(transition.moveSourceTaskToBack)
        assertFalse(transition.reuseTargetTask)
        assertFalse(transition.retireSourceAfterLaunch)
    }

    @Test
    fun fullscreenReturnsToExistingStandardTaskThenClosesItself() {
        val transition = CastWindowPolicy.transitionFrom(CastWindowMode.FULLSCREEN)

        assertEquals(CastWindowMode.STANDARD, transition.target)
        assertFalse(transition.moveSourceTaskToBack)
        assertTrue(transition.reuseTargetTask)
        assertTrue(transition.retireSourceAfterLaunch)
    }

    @Test
    fun targetLaunchFailureDoesNotRetireSource() {
        var sourceRetired = false
        var targetFailureReported = false
        var retirementFailureReported = false

        val launched = executeWindowTransition(
            launchTarget = { throw IllegalStateException("launch rejected") },
            retireSource = { sourceRetired = true },
            onTargetLaunchFailure = { targetFailureReported = true },
            onSourceRetirementFailure = { retirementFailureReported = true },
        )

        assertFalse(launched)
        assertFalse(sourceRetired)
        assertTrue(targetFailureReported)
        assertFalse(retirementFailureReported)
    }

    @Test
    fun sourceRetirementFailureDoesNotInvalidateAcceptedTarget() {
        val events = mutableListOf<String>()
        var targetFailureReported = false
        var retirementFailureReported = false

        val launched = executeWindowTransition(
            launchTarget = { events += "launch" },
            retireSource = {
                events += "retire"
                throw IllegalStateException("retirement rejected")
            },
            onTargetLaunchFailure = { targetFailureReported = true },
            onSourceRetirementFailure = { retirementFailureReported = true },
        )

        assertTrue(launched)
        assertEquals(listOf("launch", "retire"), events)
        assertFalse(targetFailureReported)
        assertTrue(retirementFailureReported)
    }
}
