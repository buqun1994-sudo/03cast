package com.tcrrry.desktopcast.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPlaybackInterlockTest {
    @Test
    fun `block remains active until a new receiver lifecycle begins`() {
        val interlock = DrivingPlaybackInterlock()

        interlock.beginReceiverLifecycle()
        assertFalse(interlock.isBlocked)

        interlock.block()
        interlock.block()
        assertTrue(interlock.isBlocked)

        interlock.beginReceiverLifecycle()
        assertFalse(interlock.isBlocked)
    }
}
