package com.tcrrry.desktopcast.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address
import java.net.NetworkInterface

class LanAddressMonitor(
    context: Context,
    private val onAddressChanged: (Inet4Address?) -> Unit,
) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false
    private var lastAddress: Inet4Address? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(linkProperties(network))

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            publish(linkProperties)

        override fun onLost(network: Network) = publish(currentLinkProperties())
    }

    fun start() {
        if (registered) return
        registered = true
        connectivity.registerDefaultNetworkCallback(callback)
        publish(currentLinkProperties())
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        lastAddress = null
    }

    fun currentAddress(): Inet4Address? = select(currentLinkProperties()) ?: enumerateFallback()

    private fun currentLinkProperties(): LinkProperties? =
        connectivity.activeNetwork?.let(connectivity::getLinkProperties)

    private fun linkProperties(network: Network): LinkProperties? =
        connectivity.getLinkProperties(network)

    private fun publish(properties: LinkProperties?) {
        if (!registered) return
        val address = select(properties) ?: enumerateFallback()
        if (address == lastAddress) return
        lastAddress = address
        onAddressChanged(address)
    }

    private fun select(properties: LinkProperties?): Inet4Address? = properties?.linkAddresses
        ?.asSequence()
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { address ->
            !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
        }

    private fun enumerateFallback(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }.getOrNull()
}
