package com.tcrrry.desktopcast.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastWindowHandoffTest {
    @Test
    fun onlyCurrentTokenCanCompleteHandoff() {
        val handoff = CastWindowHandoff()
        val first = handoff.begin()
        val second = handoff.begin()

        assertFalse(handoff.complete(first))
        assertTrue(handoff.isActive)
        assertTrue(handoff.complete(second))
        assertFalse(handoff.isActive)
    }

    @Test
    fun staleTimeoutCannotCancelNewerHandoff() {
        val handoff = CastWindowHandoff()
        val first = handoff.begin()
        val second = handoff.begin()

        assertFalse(handoff.timeout(first))
        assertTrue(handoff.isActive)
        assertTrue(handoff.cancel(second))
        assertFalse(handoff.isActive)
    }
}
