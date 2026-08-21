package com.ninepointnine.desktopcast.window

import com.ninepointnine.desktopcast.session.CastContentKind
import org.junit.Assert.assertEquals
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

    @Test
    fun onlyCurrentCompletionRunsSourceRetirementOnce() {
        val handoff = CastWindowHandoff()
        var firstRetirements = 0
        var secondRetirements = 0
        val first = handoff.begin { firstRetirements += 1 }
        val second = handoff.begin { secondRetirements += 1 }

        assertFalse(handoff.complete(first))
        assertTrue(handoff.complete(second))
        assertFalse(handoff.complete(second))
        assertEquals(0, firstRetirements)
        assertEquals(1, secondRetirements)
    }

    @Test
    fun cancellationAndTimeoutDiscardSourceRetirement() {
        val handoff = CastWindowHandoff()
        var retirements = 0
        val cancelled = handoff.begin { retirements += 1 }

        assertTrue(handoff.cancel(cancelled))
        assertFalse(handoff.complete(cancelled))

        val timedOut = handoff.begin { retirements += 1 }
        assertTrue(handoff.timeout(timedOut))
        assertFalse(handoff.complete(timedOut))
        assertEquals(0, retirements)
    }

    @Test
    fun outputCommitRunsBeforeSourceRetirement() {
        val handoff = CastWindowHandoff()
        val events = mutableListOf<String>()
        val token = handoff.begin { events += "retire" }

        assertTrue(handoff.complete(token) { events += "commit" })
        assertEquals(listOf("commit", "retire"), events)
    }

    @Test
    fun videoHandoffWaitsForItsMatchingSurface() {
        assertFalse(
            isWindowHandoffOutputReady(
                content = CastContentKind.NETWORK_VIDEO,
                mirrorSurfaceReady = true,
                mediaSurfaceReady = false,
            ),
        )
        assertTrue(
            isWindowHandoffOutputReady(
                content = CastContentKind.NETWORK_VIDEO,
                mirrorSurfaceReady = false,
                mediaSurfaceReady = true,
            ),
        )
        assertFalse(
            isWindowHandoffOutputReady(
                content = CastContentKind.MIRROR,
                mirrorSurfaceReady = false,
                mediaSurfaceReady = true,
            ),
        )
        assertTrue(
            isWindowHandoffOutputReady(
                content = CastContentKind.MIRROR,
                mirrorSurfaceReady = true,
                mediaSurfaceReady = false,
            ),
        )
    }

    @Test
    fun nonVideoHandoffDoesNotWaitForSurface() {
        listOf(
            CastContentKind.NONE,
            CastContentKind.AUDIO,
            CastContentKind.IMAGE,
        ).forEach { content ->
            assertTrue(
                isWindowHandoffOutputReady(
                    content = content,
                    mirrorSurfaceReady = false,
                    mediaSurfaceReady = false,
                ),
            )
        }
    }
}
