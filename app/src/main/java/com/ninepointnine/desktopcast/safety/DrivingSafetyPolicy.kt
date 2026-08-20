package com.ninepointnine.desktopcast.safety

import com.ninepointnine.desktopcast.session.CastPhase

enum class DrivingState {
    PARKED,
    NOT_PARKED,
    UNAVAILABLE,
}

/** Maps the standard single-value VehicleGear encoding. */
object DrivingGearStateMapper {
    fun fromCurrentGear(rawGear: Int?, parkGear: Int): DrivingState = when {
        rawGear !in STANDARD_GEAR_VALUES -> DrivingState.UNAVAILABLE
        rawGear == parkGear -> DrivingState.PARKED
        else -> DrivingState.NOT_PARKED
    }

    /**
     * The S56_HQX VHAL does not publish CURRENT_GEAR, but its selector property
     * publishes the vendor value 11 while the vehicle is in P. Keep that
     * compatibility mapping isolated from the standard CURRENT_GEAR mapping.
     */
    fun fromGearSelection(rawGear: Int?, parkGear: Int): DrivingState = when {
        rawGear == TARGET_SELECTOR_PARK -> DrivingState.PARKED
        rawGear !in STANDARD_GEAR_VALUES -> DrivingState.UNAVAILABLE
        rawGear == parkGear -> DrivingState.PARKED
        else -> DrivingState.NOT_PARKED
    }

    // VehicleGear is a bit-field enum. Keep the accepted set explicit so an
    // unknown vendor bit cannot be mistaken for a confirmed driving gear.
    private val STANDARD_GEAR_VALUES = setOf(
        1,    // GEAR_NEUTRAL
        2,    // GEAR_REVERSE
        4,    // GEAR_PARK
        8,    // GEAR_DRIVE
        16,   // GEAR_FIRST
        32,   // GEAR_SECOND
        64,   // GEAR_THIRD
        128,  // GEAR_FOURTH
        256,  // GEAR_FIFTH
        512,  // GEAR_SIXTH
        1024, // GEAR_SEVENTH
        2048, // GEAR_EIGHTH
        4096, // GEAR_NINTH
    )

    private const val TARGET_SELECTOR_PARK = 11
}

data class DrivingSafetyAlert(
    val secondsRemaining: Int,
    val exitRequired: Boolean = false,
)

object DrivingSafetyPolicy {
    fun shouldBeginExit(
        protectionEnabled: Boolean,
        drivingState: DrivingState,
        castPhase: CastPhase,
    ): Boolean = protectionEnabled &&
        drivingState == DrivingState.NOT_PARKED &&
        castPhase in ACTIVE_PHASES

    private val ACTIVE_PHASES = setOf(
        CastPhase.CONNECTING,
        CastPhase.PLAYING,
        CastPhase.MIRRORING,
        CastPhase.AUDIO,
    )
}
