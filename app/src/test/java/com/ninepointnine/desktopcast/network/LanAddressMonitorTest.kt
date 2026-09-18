package com.ninepointnine.desktopcast.network

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressMonitorTest {
    @Test
    fun vpnAddressIsRejectedEvenWhenItIsTheValidatedDefaultNetwork() {
        val selected = LanAddressSelector.choose(
            listOf(
                candidate("10.10.0.2", "tun0", LanTransport.WIFI, validated = true),
                candidate("10.57.142.203", "wlan0", LanTransport.WIFI, validated = true),
            ),
        )

        assertEquals("10.57.142.203", selected?.hostAddress)
    }

    @Test
    fun validatedWifiWinsOverUnvalidatedSecondaryPhysicalNetwork() {
        val selected = LanAddressSelector.choose(
            listOf(
                candidate("192.168.1.3", "eth0", LanTransport.ETHERNET, validated = false),
                candidate("10.57.142.203", "wlan0", LanTransport.WIFI, validated = true),
            ),
        )

        assertEquals("10.57.142.203", selected?.hostAddress)
    }

    @Test
    fun defaultRouteWinsWhenTwoPhysicalWifiNetworksArePresent() {
        val selected = LanAddressSelector.choose(
            listOf(
                candidate("192.168.42.102", "wlan1", LanTransport.WIFI, validated = true),
                candidate(
                    "10.57.142.203",
                    "wlan0",
                    LanTransport.WIFI,
                    validated = false,
                    defaultRoute = true,
                ),
            ),
        )

        assertEquals("10.57.142.203", selected?.hostAddress)
    }

    @Test
    fun tunnelOnlyCandidatesProduceNoLanAddress() {
        val selected = LanAddressSelector.choose(
            listOf(
                candidate("10.10.0.2", "tun0", LanTransport.OTHER, validated = true),
                candidate("10.10.0.3", "rmnet_data0", LanTransport.OTHER, validated = true),
            ),
        )

        assertNull(selected)
    }

    private fun candidate(
        address: String,
        interfaceName: String,
        transport: LanTransport,
        validated: Boolean,
        defaultRoute: Boolean = false,
    ) = LanAddressCandidate(
        address = InetAddress.getByName(address) as Inet4Address,
        interfaceName = interfaceName,
        transport = transport,
        validated = validated,
        defaultRoute = defaultRoute,
    )
}
