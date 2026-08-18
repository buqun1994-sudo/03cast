package com.tcrrry.desktopcast.safety

import com.tcrrry.desktopcast.session.CastPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSafetyPolicyTest {
    @Test
    fun `current gear mapping rejects the composite value reported by the selector in park`() {
        assertEquals(DrivingState.PARKED, DrivingGearStateMapper.fromCurrentGear(4, parkGear = 4))
        assertEquals(DrivingState.NOT_PARKED, DrivingGearStateMapper.fromCurrentGear(8, parkGear = 4))
        assertEquals(DrivingState.NOT_PARKED, DrivingGearStateMapper.fromCurrentGear(2, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromCurrentGear(11, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromCurrentGear(8192, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromCurrentGear(0, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromCurrentGear(null, parkGear = 4))
    }

    @Test
    fun `target selector mapping treats the S56 park code as parked`() {
        assertEquals(DrivingState.PARKED, DrivingGearStateMapper.fromGearSelection(11, parkGear = 4))
        assertEquals(DrivingState.NOT_PARKED, DrivingGearStateMapper.fromGearSelection(8, parkGear = 4))
        assertEquals(DrivingState.NOT_PARKED, DrivingGearStateMapper.fromGearSelection(2, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromGearSelection(8192, parkGear = 4))
        assertEquals(DrivingState.UNAVAILABLE, DrivingGearStateMapper.fromGearSelection(null, parkGear = 4))
    }

    @Test
    fun `blocks active playback only for a known non-park state`() {
        assertTrue(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.NOT_PARKED,
                castPhase = CastPhase.PLAYING,
            ),
        )
        assertTrue(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.NOT_PARKED,
                castPhase = CastPhase.CONNECTING,
            ),
        )
        assertTrue(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.NOT_PARKED,
                castPhase = CastPhase.MIRRORING,
            ),
        )
        assertFalse(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.PARKED,
                castPhase = CastPhase.PLAYING,
            ),
        )
        assertFalse(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.UNAVAILABLE,
                castPhase = CastPhase.PLAYING,
            ),
        )
        assertFalse(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = false,
                drivingState = DrivingState.NOT_PARKED,
                castPhase = CastPhase.PLAYING,
            ),
        )
        assertFalse(
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = true,
                drivingState = DrivingState.NOT_PARKED,
                castPhase = CastPhase.WAITING,
            ),
        )
    }
}
