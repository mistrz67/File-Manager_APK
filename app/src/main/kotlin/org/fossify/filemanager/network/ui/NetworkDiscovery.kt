package org.fossify.filemanager.network.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.fossify.filemanager.network.core.DiscoveredServer
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.core.SubnetScanner
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for file servers on the Wi-Fi network the phone is connected to, in two ways at once: Bonjour/mDNS
 * announcements (which carry the server's name) and a quick port scan of the local /24 network (which also finds
 * servers that do not announce themselves). Results are reported on the main thread.
 */
class NetworkDiscovery(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onServer(server: DiscoveredServer)

        fun onProgress(percent: Int)

        /** [hasNetwork] is false when the phone has no local IPv4 network to scan. */
        fun onFinished(hasNetwork: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val discoveryListeners = ArrayList<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        val network = findLocalNetwork()
        if (network == null) {
            main.post { if (!cancelled.get()) listener.onFinished(hasNetwork = false) }
            return
        }

        startNsd()
        Thread({
            val hosts = SubnetScanner.hostsAround(network.first, network.second)
            SubnetScanner().scan(
                hosts,
                object : SubnetScanner.Listener {
                    override fun onServerFound(server: DiscoveredServer) {
                        main.post { if (!cancelled.get()) listener.onServer(server) }
                    }

                    override fun onProgress(scanned: Int, total: Int) {
                        if (scanned % 8 == 0 || scanned == total) {
                            main.post { if (!cancelled.get()) listener.onProgress(scanned * 100 / total) }
                        }
                    }
                },
                cancelled,
            )
            main.postDelayed({
                stopNsd()
                if (!cancelled.get()) listener.onFinished(hasNetwork = true)
            }, NSD_GRACE_MS)
        }, "network-drive-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        cancelled.set(true)
        stopNsd()
    }

    /** The phone's IPv4 address and prefix length on its active network, or null when there is none. */
    private fun findLocalNetwork(): Pair<String, Int>? {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val properties = manager.getLinkProperties(manager.activeNetwork) ?: return null
            properties.linkAddresses
                .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { (it.address.hostAddress ?: return null) to it.prefixLength }
        } catch (e: Exception) {
            null
        }
    }

    // region mDNS

    private fun startNsd() {
        val manager = nsd ?: return
        SERVICE_TYPES.forEach { (type, protocol) ->
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { enqueueResolve(info, protocol) }
                }

                override fun onServiceLost(info: NsdServiceInfo?) {}

                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
                discoveryListeners.add(discovery)
            } catch (e: Exception) {
                // discovery is a bonus; the port scan still works
            }
        }
    }

    private fun stopNsd() {
        val manager = nsd ?: return
        discoveryListeners.forEach {
            try {
                manager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // already stopped
            }
        }
        discoveryListeners.clear()
        resolveQueue.clear()
    }

    private val protocolByService = HashMap<String, Protocol>()

    private fun enqueueResolve(info: NsdServiceInfo, protocol: Protocol) {
        if (cancelled.get()) return
        protocolByService[info.serviceName] = protocol
        resolveQueue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val manager = nsd ?: return
        resolving = true
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val address = resolved.host?.hostAddress
                        val protocol = protocolByService[resolved.serviceName] ?: Protocol.SMB
                        if (address != null && !cancelled.get() && resolved.host is Inet4Address) {
                            listener.onServer(DiscoveredServer(address, resolved.serviceName, setOf(protocol)))
                        }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    // endregion

    private companion object {
        const val NSD_GRACE_MS = 1_500L

        val SERVICE_TYPES = listOf(
            "_smb._tcp." to Protocol.SMB,
            "_sftp-ssh._tcp." to Protocol.SFTP,
            "_ftp._tcp." to Protocol.FTP,
        )
    }
}
