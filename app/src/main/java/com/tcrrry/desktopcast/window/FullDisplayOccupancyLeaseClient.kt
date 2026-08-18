package com.tcrrry.desktopcast.window

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder

/**
 * Holds a versioned lease with every compatible overlay provider while this
 * application owns the full display. The provider binding itself is the lease.
 */
class FullDisplayOccupancyLeaseClient(context: Context) {
    private val coordinator = FullDisplayOccupancyLeaseCoordinator(
        AndroidLeaseGateway(context.applicationContext),
    )

    fun acquire() = coordinator.acquire()

    fun release() = coordinator.release()

    private class AndroidLeaseGateway(
        private val context: Context,
    ) : FullDisplayOccupancyLeaseCoordinator.Gateway {
        override fun discoverProviderIds(): List<String> {
            val providers = try {
                context.packageManager.queryIntentServices(
                    Intent(ACTION_ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE),
                    PackageManager.GET_META_DATA,
                )
            } catch (_: RuntimeException) {
                emptyList()
            }
            return providers.mapNotNull { resolveInfo ->
                try {
                    val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                    val version = serviceInfo.metaData
                        ?.getInt(METADATA_FULL_DISPLAY_OCCUPANCY_PROTOCOL_VERSION, -1)
                        ?: -1
                    if (!serviceInfo.exported || version != PROTOCOL_VERSION) {
                        return@mapNotNull null
                    }
                    ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToString()
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        override fun acquire(providerId: String): FullDisplayOccupancyLeaseCoordinator.Lease? {
            val component = ComponentName.unflattenFromString(providerId) ?: return null
            return BoundServiceLease(context, component).takeIf(BoundServiceLease::start)
        }
    }

    private class BoundServiceLease(
        private val context: Context,
        private val component: ComponentName,
    ) : ServiceConnection, FullDisplayOccupancyLeaseCoordinator.Lease {
        private var requested = false
        private var bound = false

        fun start(): Boolean {
            if (requested) return bound
            requested = true
            bound = bind()
            if (!bound) requested = false
            return bound
        }

        override fun release() {
            if (!requested && !bound) return
            requested = false
            unbind()
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName) = Unit

        override fun onBindingDied(name: ComponentName) {
            if (!requested) return
            unbind()
            bound = bind()
            if (!bound) requested = false
        }

        override fun onNullBinding(name: ComponentName) {
            requested = false
            unbind()
        }

        private fun bind(): Boolean = try {
            context.bindService(
                Intent(ACTION_ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE).setComponent(component),
                this,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // The provider already discarded this binding.
            } catch (_: RuntimeException) {
                // Local lease state is released even if the provider disappeared.
            }
        }
    }

    companion object {
        const val ACTION_ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE =
            "com.tcrrry.icar.surface.action.ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE"
        const val METADATA_FULL_DISPLAY_OCCUPANCY_PROTOCOL_VERSION =
            "com.tcrrry.icar.surface.FULL_DISPLAY_OCCUPANCY_PROTOCOL_VERSION"
        const val PROTOCOL_VERSION = 1
    }
}

internal class FullDisplayOccupancyLeaseCoordinator(
    private val gateway: Gateway,
) {
    interface Lease {
        fun release()
    }

    interface Gateway {
        fun discoverProviderIds(): List<String>
        fun acquire(providerId: String): Lease?
    }

    private val leases = linkedMapOf<String, Lease>()
    private var requested = false

    fun acquire() {
        if (requested) return
        requested = true
        val providerIds = try {
            gateway.discoverProviderIds().distinct()
        } catch (_: RuntimeException) {
            emptyList()
        }
        providerIds.forEach { providerId ->
            val lease = try {
                gateway.acquire(providerId)
            } catch (_: RuntimeException) {
                null
            }
            if (lease != null) leases[providerId] = lease
        }
    }

    fun release() {
        if (!requested && leases.isEmpty()) return
        requested = false
        val activeLeases = leases.values.toList()
        leases.clear()
        activeLeases.forEach { lease ->
            try {
                lease.release()
            } catch (_: RuntimeException) {
                // One failed provider cannot keep another provider's lease alive.
            }
        }
    }
}
