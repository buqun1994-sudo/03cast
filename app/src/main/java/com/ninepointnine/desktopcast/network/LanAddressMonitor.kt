package com.ninepointnine.desktopcast.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

internal enum class LanTransport {
    WIFI,
    ETHERNET,
    OTHER,
}

internal data class LanAddressCandidate(
    val address: Inet4Address,
    val interfaceName: String?,
    val transport: LanTransport,
    val validated: Boolean,
    val defaultRoute: Boolean = false,
    val network: Network? = null,
)

/** Picks a physical LAN address and explicitly excludes VPN / tunnel paths. */
internal object LanAddressSelector {
    fun chooseCandidate(candidates: Iterable<LanAddressCandidate>): LanAddressCandidate? = candidates
        .filter { candidate ->
            usableAddress(candidate.address) && !isVirtualInterface(candidate.interfaceName)
        }
        .maxWithOrNull(
            compareBy<LanAddressCandidate> { score(it) }
                .thenBy { it.interfaceName ?: "" }
                .thenBy { it.address.hostAddress ?: "" },
        )

    fun choose(candidates: Iterable<LanAddressCandidate>): Inet4Address? =
        chooseCandidate(candidates)?.address

    private fun score(candidate: LanAddressCandidate): Int {
        val transportScore = when (candidate.transport) {
            LanTransport.WIFI -> 300
            LanTransport.ETHERNET -> 250
            LanTransport.OTHER -> 100
        }
        return transportScore +
            (if (candidate.defaultRoute) 60 else 0) +
            (if (candidate.validated) 20 else 0)
    }

    private fun usableAddress(address: Inet4Address): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress

    private fun isVirtualInterface(name: String?): Boolean {
        val normalized = name?.lowercase() ?: return false
        return normalized.startsWith("tun") ||
            normalized.startsWith("ppp") ||
            normalized.startsWith("dummy") ||
            normalized == "lo" ||
            normalized.startsWith("rmnet") ||
            normalized.startsWith("ccmni") ||
            normalized.startsWith("wwan")
    }
}

class LanAddressMonitor(
    context: Context,
    private val onAddressChanged: (Inet4Address?) -> Unit,
) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false
    private var callbackRegistered = false
    private var lastAddress: Inet4Address? = null
    private var lastNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            publish()

        override fun onLost(network: Network) = publish()
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback,
            )
            callbackRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Unable to register physical LAN callback", error)
        }
        publish()
    }

    fun stop() {
        if (!registered) return
        registered = false
        if (callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            callbackRegistered = false
        }
        lastAddress = null
        lastNetwork = null
    }

    fun currentAddress(): Inet4Address? = resolveBestAddress()

    /** Returns the physical Android network selected for media sockets. */
    fun currentNetwork(): Network? = resolveBestCandidate()?.network

    private fun publish() {
        if (!registered) return
        val candidate = resolveBestCandidate()
        val address = candidate?.address
        val network = candidate?.network
        if (address == lastAddress && network == lastNetwork) return
        lastAddress = address
        lastNetwork = network
        Log.i(
            TAG,
            "Physical LAN selected: address=${address?.hostAddress ?: "none"}, " +
                "interface=${candidate?.interfaceName ?: "none"}, " +
                "network=${network ?: "interface-fallback"}",
        )
        onAddressChanged(address)
    }

    private fun resolveBestAddress(): Inet4Address? = resolveBestCandidate()?.address

    private fun resolveBestCandidate(): LanAddressCandidate? {
        val candidates = connectivity.allNetworks.asSequence().mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val defaultRoute = properties.routes.any { it.isDefaultRoute }
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanTransport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanTransport.ETHERNET
                else -> LanTransport.OTHER
            }
            properties.linkAddresses.asSequence()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .map { address ->
                    LanAddressCandidate(
                        address = address,
                        interfaceName = properties.interfaceName,
                        transport = transport,
                        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        defaultRoute = defaultRoute,
                        network = network,
                    )
                }
        }.flatMap { it }
        return LanAddressSelector.chooseCandidate(candidates.toList()) ?: enumerateFallbackCandidate()
    }

    private fun enumerateFallbackCandidate(): LanAddressCandidate? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                val transport = when {
                    networkInterface.name.startsWith("wlan") ||
                        networkInterface.name.startsWith("p2p") -> LanTransport.WIFI
                    networkInterface.name.startsWith("eth") ||
                        networkInterface.name.startsWith("usb") -> LanTransport.ETHERNET
                    else -> LanTransport.OTHER
                }
                networkInterface.inetAddresses.toList().asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { address ->
                        LanAddressCandidate(address, networkInterface.name, transport, validated = false)
                    }
            }
        LanAddressSelector.chooseCandidate(candidates.toList())
    }.getOrNull()

    private companion object {
        const val TAG = "LanAddressMonitor"
    }
}
