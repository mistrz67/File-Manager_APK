package org.fossify.filemanager.network.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A machine found on the local network that offers a file sharing service. */
data class DiscoveredServer(
    val address: String,
    val hostName: String?,
    val protocols: Set<Protocol>,
) {
    val displayName: String get() = hostName?.takeIf { it.isNotBlank() && it != address } ?: address
}

/**
 * Finds file servers by trying to connect to their well known ports. Works without any special permissions and on
 * networks where multicast discovery (mDNS) is blocked; it complements the name based discovery done by the UI layer.
 */
class SubnetScanner(
    private val connectTimeoutMs: Int = 350,
    private val threads: Int = 48,
    /** Ports to probe and the protocol they indicate. */
    private val ports: Map<Int, Protocol> = DEFAULT_PORTS,
    private val resolveNames: Boolean = true,
) {
    interface Listener {
        fun onServerFound(server: DiscoveredServer)
        fun onProgress(scanned: Int, total: Int) {}
    }

    /** Scans [addresses] (e.g. all hosts of a /24 network). Blocks until done or [cancelled] is set. */
    fun scan(addresses: List<String>, listener: Listener, cancelled: AtomicBoolean = AtomicBoolean(false)) {
        val found = ConcurrentHashMap<String, MutableSet<Protocol>>()
        val reported = ConcurrentHashMap<String, Boolean>()
        val executor = Executors.newFixedThreadPool(threads.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "network-drive-scan").apply { isDaemon = true }
        }

        val scanned = java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures = addresses.map { address ->
                executor.submit {
                    if (cancelled.get()) return@submit
                    val protocols = probe(address, cancelled)
                    if (protocols.isNotEmpty()) {
                        found[address] = protocols.toMutableSet()
                        if (reported.putIfAbsent(address, true) == null) {
                            val name = if (resolveNames) lookupName(address) else null
                            listener.onServerFound(DiscoveredServer(address, name, protocols))
                        }
                    }
                    listener.onProgress(scanned.incrementAndGet(), addresses.size)
                }
            }
            futures.forEach {
                try {
                    it.get()
                } catch (ignored: Exception) {
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probe(address: String, cancelled: AtomicBoolean): Set<Protocol> {
        val result = HashSet<Protocol>()
        for ((port, protocol) in ports) {
            if (cancelled.get()) break
            if (isOpen(address, port)) result.add(protocol)
        }
        return result
    }

    private fun isOpen(address: String, port: Int): Boolean {
        return try {
            Socket().use {
                it.connect(InetSocketAddress(address, port), connectTimeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun lookupName(address: String): String? {
        return try {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "network-drive-dns").apply { isDaemon = true } }
            try {
                val name = executor.submit<String?> { InetAddress.getByName(address).canonicalHostName }.get(800, TimeUnit.MILLISECONDS)
                name?.takeIf { it != address }
            } finally {
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        val DEFAULT_PORTS: Map<Int, Protocol> = linkedMapOf(
            445 to Protocol.SMB,
            22 to Protocol.SFTP,
            21 to Protocol.FTP,
        )

        /**
         * All usable host addresses of the IPv4 network the address [localAddress] belongs to, limited to the /24
         * around it for large networks, excluding the device itself.
         */
        fun hostsAround(localAddress: String, prefixLength: Int): List<String> {
            val address = InetAddress.getByName(localAddress)
            if (address !is Inet4Address) return emptyList()

            val bytes = address.address
            val ip = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
                ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
            val effectivePrefix = prefixLength.coerceIn(24, 30)
            val mask = (-1 shl (32 - effectivePrefix))
            val network = ip and mask
            val broadcast = network or mask.inv()

            val hosts = ArrayList<String>()
            for (candidate in (network + 1) until broadcast) {
                if (candidate == ip) continue
                hosts.add(
                    "${(candidate ushr 24) and 0xff}.${(candidate ushr 16) and 0xff}.${(candidate ushr 8) and 0xff}.${candidate and 0xff}"
                )
            }
            return hosts
        }
    }
}
