package com.ninepointnine.desktopcast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenaSubscriptionRegistryTest {
    @Test
    fun subscribeRenewSequenceAndUnsubscribe() {
        var now = 1_000L
        val registry = GenaSubscriptionRegistry { now }

        val initial = registry.subscribe(
            DlnaService.AV_TRANSPORT,
            "<http://192.168.0.10:1400/events>",
            "Second-300",
        )
        assertEquals(1, registry.size())
        assertEquals(0L, registry.next(initial.sid)?.sequence)
        assertEquals(1L, registry.next(initial.sid)?.sequence)

        now += 100_000
        val renewed = registry.renew(initial.sid, "Second-600")
        assertTrue(renewed.expiresAtMs > initial.expiresAtMs)

        registry.unsubscribe(initial.sid)
        assertEquals(0, registry.size())
    }

    @Test
    fun expiredSubscriptionIsRemoved() {
        var now = 0L
        val registry = GenaSubscriptionRegistry { now }
        registry.subscribe(DlnaService.RENDERING_CONTROL, "<http://host/event>", "Second-60")

        now = 61_000

        assertEquals(0, registry.size())
    }
}
