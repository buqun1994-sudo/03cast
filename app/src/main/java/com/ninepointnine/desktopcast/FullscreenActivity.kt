package com.ninepointnine.desktopcast

import android.os.Bundle
import com.ninepointnine.desktopcast.window.FullDisplayOccupancyLeaseClient

class FullscreenActivity : MainActivity() {
    override val isFullscreenWindow: Boolean = true

    private lateinit var fullDisplayOccupancyLease: FullDisplayOccupancyLeaseClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullDisplayOccupancyLease = FullDisplayOccupancyLeaseClient(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        fullDisplayOccupancyLease.acquire()
    }

    override fun onStop() {
        super.onStop()
        fullDisplayOccupancyLease.release()
    }

    override fun onDestroy() {
        fullDisplayOccupancyLease.release()
        super.onDestroy()
    }
}
