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
        assertFalse(transition.finishSourceTask)
    }

    @Test
    fun fullscreenReturnsToExistingStandardTaskThenClosesItself() {
        val transition = CastWindowPolicy.transitionFrom(CastWindowMode.FULLSCREEN)

        assertEquals(CastWindowMode.STANDARD, transition.target)
        assertFalse(transition.moveSourceTaskToBack)
        assertTrue(transition.reuseTargetTask)
        assertTrue(transition.finishSourceTask)
    }
}
