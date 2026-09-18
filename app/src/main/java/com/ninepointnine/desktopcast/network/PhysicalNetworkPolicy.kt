package com.ninepointnine.desktopcast.network

import android.net.LinkProperties
import android.net.Network
import java.net.InetAddress
import java.net.URL
import java.net.URLConnection

/**
 * Keeps sender-local HTTP traffic on the physical LAN without changing the
 * process-wide route. Public media deliberately remains on the system default
 * network so the receiver does not take over the user's VPN policy.
 */
internal object PhysicalNetworkPolicy {
    fun openConnection(
        url: URL,
        network: Network?,
        linkPropertiesProvider: (Network) -> LinkProperties? = { null },
    ): URLConnection? {
        val selected = network ?: return null
        val host = url.host.takeIf(String::isNotBlank) ?: return null
        val addresses = runCatching { selected.getAllByName(host).toList() }.getOrNull() ?: return null
        val linkProperties = linkPropertiesProvider(selected)
        val matchesPhysicalRoute = addresses.any { address ->
            linkProperties?.routes?.any { route ->
                !route.isDefaultRoute && route.matches(address)
            } ?: isLocalAddress(address)
        }
        if (!matchesPhysicalRoute) return null
        return selected.openConnection(url)
    }

    private fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress) return false
        if (address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val first = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        return address.address.size == 16 && first and 0xfe == 0xfc
    }
}
