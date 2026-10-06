package org.fossify.filemanager.network.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class SubnetScannerTest {
    @Test
    fun hostsAroundListsTheWholeSlash24WithoutTheDevice() {
        val hosts = SubnetScanner.hostsAround("192.168.1.20", 24)
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.20" in hosts)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun largeNetworksAreLimitedToTheSlash24AroundTheDevice() {
        val hosts = SubnetScanner.hostsAround("10.1.2.3", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.1.2.") })
    }

    @Test
    fun smallNetworksAreRespected() {
        val hosts = SubnetScanner.hostsAround("172.16.0.5", 29)
        assertEquals(listOf("172.16.0.1", "172.16.0.2", "172.16.0.3", "172.16.0.4", "172.16.0.6"), hosts)
    }

    @Test
    fun ipv6IsIgnored() {
        assertTrue(SubnetScanner.hostsAround("::1", 64).isEmpty())
    }

    @Test
    fun findsAHostWithAnOpenPort() {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            val scanner = SubnetScanner(connectTimeoutMs = 300, ports = mapOf(server.localPort to Protocol.SMB), resolveNames = false)
            val found = CopyOnWriteArrayList<DiscoveredServer>()
            scanner.scan(listOf("127.0.0.1", "127.0.0.2"), object : SubnetScanner.Listener {
                override fun onServerFound(server: DiscoveredServer) { found.add(server) }
            })

            // 127.0.0.2 is also a loopback address on Linux; either way 127.0.0.1 must be reported
            assertTrue(found.any { it.address == "127.0.0.1" && it.protocols == setOf(Protocol.SMB) })
        }
    }

    @Test
    fun reportsNothingForClosedPorts() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val scanner = SubnetScanner(connectTimeoutMs = 200, ports = mapOf(closedPort to Protocol.FTP), resolveNames = false)
        val found = CopyOnWriteArrayList<DiscoveredServer>()
        scanner.scan(listOf("127.0.0.1"), object : SubnetScanner.Listener {
            override fun onServerFound(server: DiscoveredServer) { found.add(server) }
        })
        assertTrue(found.isEmpty())
    }
}
