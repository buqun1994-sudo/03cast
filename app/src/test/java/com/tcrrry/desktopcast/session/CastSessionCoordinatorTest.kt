package com.tcrrry.desktopcast.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSessionCoordinatorTest {
    @Test
    fun startAndReadyEnterWaiting() {
        val coordinator = CastSessionCoordinator()

        coordinator.beginStart()
        assertEquals(CastPhase.STARTING, coordinator.state.value.phase)

        coordinator.ready()
        assertEquals(CastSessionState(phase = CastPhase.WAITING), coordinator.state.value)
    }

    @Test
    fun newProtocolAtomicallyClaimsOldSession() {
        val coordinator = CastSessionCoordinator()
        coordinator.beginStart()
        coordinator.ready()

        val airPlayLease = coordinator.beginSession(CastProtocol.AIRPLAY)
        assertNull(airPlayLease?.previous)
        coordinator.showContent(CastProtocol.AIRPLAY, CastContentKind.MIRROR)
        assertEquals(CastPhase.MIRRORING, coordinator.state.value.phase)

        val dlnaLease = coordinator.beginSession(CastProtocol.DLNA)
        assertEquals(CastProtocol.AIRPLAY, dlnaLease?.previous)
        assertTrue(dlnaLease!!.generation > airPlayLease!!.generation)
        assertEquals(CastPhase.CONNECTING, coordinator.state.value.phase)
        assertEquals(CastProtocol.DLNA, coordinator.state.value.protocol)
        assertEquals(CastContentKind.NONE, coordinator.state.value.content)
    }

    @Test
    fun sameProtocolNewSessionInvalidatesOldLeaseAndClearsContent() {
        val coordinator = activeAudioCoordinator()
        val oldLease = coordinator.leaseFor(CastProtocol.AIRPLAY)!!

        val newLease = coordinator.beginSession(CastProtocol.AIRPLAY)!!

        assertNull(newLease.previous)
        assertTrue(newLease.generation > oldLease.generation)
        assertFalse(coordinator.isCurrent(oldLease))
        assertEquals(CastPhase.CONNECTING, coordinator.state.value.phase)
        assertEquals(CastContentKind.NONE, coordinator.state.value.content)
    }

    @Test
    fun leaseLookupReusesCurrentGenerationWithoutChangingState() {
        val coordinator = activeAudioCoordinator()
        val state = coordinator.state.value

        val first = coordinator.leaseFor(CastProtocol.AIRPLAY)
        val second = coordinator.leaseFor(CastProtocol.AIRPLAY)

        assertEquals(first, second)
        assertEquals(state, coordinator.state.value)
    }

    @Test
    fun staleDisconnectCannotClearNewProtocol() {
        val coordinator = activeAudioCoordinator()
        coordinator.beginSession(CastProtocol.DLNA)
        coordinator.showContent(CastProtocol.DLNA, CastContentKind.NETWORK_VIDEO)

        coordinator.disconnected(CastProtocol.AIRPLAY)

        assertEquals(CastProtocol.DLNA, coordinator.state.value.protocol)
        assertEquals(CastPhase.PLAYING, coordinator.state.value.phase)
    }

    @Test
    fun activeDisconnectReturnsToWaiting() {
        val coordinator = activeAudioCoordinator()

        coordinator.disconnected(CastProtocol.AIRPLAY)

        assertEquals(CastSessionState(phase = CastPhase.WAITING), coordinator.state.value)
    }

    @Test
    fun stoppedCoordinatorIgnoresNewClaims() {
        val coordinator = activeAudioCoordinator()
        coordinator.stop()

        assertNull(coordinator.beginSession(CastProtocol.DLNA))
        assertNull(coordinator.leaseFor(CastProtocol.DLNA))
        coordinator.showContent(CastProtocol.DLNA, CastContentKind.NETWORK_VIDEO)

        assertEquals(CastPhase.STOPPED, coordinator.state.value.phase)
    }

    @Test
    fun olderLeaseCannotMutateAfterTakeover() {
        val coordinator = activeAudioCoordinator()
        val oldLease = coordinator.beginSession(CastProtocol.AIRPLAY)!!
        coordinator.beginSession(CastProtocol.DLNA)

        assertFalse(coordinator.isCurrent(oldLease))
    }

    @Test
    fun stopInvalidatesCurrentLease() {
        val coordinator = activeAudioCoordinator()
        val lease = coordinator.beginSession(CastProtocol.AIRPLAY)!!

        coordinator.stop()

        assertFalse(coordinator.isCurrent(lease))
    }

    private fun activeAudioCoordinator() = CastSessionCoordinator().apply {
        beginStart()
        ready()
        beginSession(CastProtocol.AIRPLAY)
        showContent(CastProtocol.AIRPLAY, CastContentKind.AUDIO)
    }
}
