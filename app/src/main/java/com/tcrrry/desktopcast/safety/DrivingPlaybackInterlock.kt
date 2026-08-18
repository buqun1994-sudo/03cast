package com.tcrrry.desktopcast.safety

/** One-way playback gate for a single receiver lifecycle. */
class DrivingPlaybackInterlock {
    var isBlocked: Boolean = false
        private set

    fun beginReceiverLifecycle() {
        isBlocked = false
    }

    fun block() {
        isBlocked = true
    }
}
