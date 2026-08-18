package com.tcrrry.desktopcast.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDisplayOccupancyLeaseCoordinatorTest {
    @Test
    fun noProvidersKeepsFullscreenAvailable() {
        val gateway = FakeGateway(emptyList())
        val coordinator = FullDisplayOccupancyLeaseCoordinator(gateway)

        coordinator.acquire()
        coordinator.release()

        assertTrue(gateway.acquireCalls.isEmpty())
    }

    @Test
    fun acquireIsIdempotentAndReleaseClosesEveryDistinctLease() {
        val gateway = FakeGateway(listOf("lyrics", "lyrics", "other"))
        val coordinator = FullDisplayOccupancyLeaseCoordinator(gateway)

        coordinator.acquire()
        coordinator.acquire()
        coordinator.release()
        coordinator.release()

        assertEquals(listOf("lyrics", "other"), gateway.acquireCalls)
        assertEquals(listOf("lyrics", "other"), gateway.releaseCalls)
    }

    @Test
    fun oneProviderFailureDoesNotBlockOtherProviders() {
        val gateway = FakeGateway(listOf("broken-acquire", "broken-release", "healthy"))
        val coordinator = FullDisplayOccupancyLeaseCoordinator(gateway)

        coordinator.acquire()
        coordinator.release()

        assertEquals(listOf("broken-acquire", "broken-release", "healthy"), gateway.acquireCalls)
        assertEquals(listOf("broken-release", "healthy"), gateway.releaseCalls)
    }

    private class FakeGateway(
        private val providerIds: List<String>,
    ) : FullDisplayOccupancyLeaseCoordinator.Gateway {
        val acquireCalls = mutableListOf<String>()
        val releaseCalls = mutableListOf<String>()

        override fun discoverProviderIds(): List<String> = providerIds

        override fun acquire(providerId: String): FullDisplayOccupancyLeaseCoordinator.Lease? {
            acquireCalls += providerId
            if (providerId == "broken-acquire") throw IllegalStateException("broken acquire")
            return object : FullDisplayOccupancyLeaseCoordinator.Lease {
                override fun release() {
                    releaseCalls += providerId
                    if (providerId == "broken-release") throw IllegalStateException("broken release")
                }
            }
        }
    }
}
