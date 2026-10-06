package org.fossify.filemanager.network.core

import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps connected [RemoteClient]s per drive so browsing does not log in again for every folder, and bounds the
 * number of parallel connections to one server. Clients are exclusive while leased and closed after sitting
 * idle for [idleTimeoutMs].
 */
class ClientPool(
    private val clientFactory: (ConnectionConfig) -> RemoteClient = { RemoteClientFactory.create(it) },
    private val maxClients: (Protocol) -> Int = { defaultMaxClients(it) },
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val probeAfterIdleMs: Long = DEFAULT_PROBE_AFTER_IDLE_MS,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    evictionIntervalMs: Long = DEFAULT_EVICTION_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    private class Idle(val client: RemoteClient, val since: Long)

    private class Bucket {
        val idle = ArrayDeque<Idle>()
        var total = 0
    }

    private val lock = ReentrantLock()
    private val slotFreed = lock.newCondition()
    private val buckets = HashMap<String, Bucket>()
    private var closed = false

    private val evictor: ScheduledExecutorService? = if (evictionIntervalMs > 0) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "network-drive-pool").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ evictIdle() }, evictionIntervalMs, evictionIntervalMs, TimeUnit.MILLISECONDS) }
    } else {
        null
    }

    /** A leased client. Call [release] when done, or [discard] when the client may be in a broken state. */
    inner class Lease internal constructor(
        val client: RemoteClient,
        private val key: String,
        /** True when the client was connected before and had been idle in the pool. */
        val wasReused: Boolean,
    ) : Closeable {
        private val finished = AtomicBoolean(false)

        fun release() {
            if (finished.compareAndSet(false, true)) giveBack(key, client)
        }

        fun discard() {
            if (finished.compareAndSet(false, true)) drop(key, client)
        }

        override fun close() = release()
    }

    /** Leases a connected client, waiting for a free slot when the limit for this drive is reached. */
    fun acquire(config: ConnectionConfig): Lease {
        val key = keyOf(config)
        val limit = maxClients(config.connection.protocol).coerceAtLeast(1)
        val deadline = clock() + acquireTimeoutMs

        while (true) {
            var candidate: Idle? = null
            var reserved = false
            lock.withLock {
                check(!closed) { "Pool is closed" }
                val bucket = buckets.getOrPut(key) { Bucket() }
                val idle = bucket.idle.pollFirst() // most recently used first
                if (idle != null) {
                    candidate = idle
                } else if (bucket.total < limit) {
                    bucket.total++
                    reserved = true
                } else {
                    val remaining = deadline - clock()
                    if (remaining <= 0) throw ConnectionFailedException("Too many open connections to ${config.connection.host}")
                    slotFreed.await(remaining, TimeUnit.MILLISECONDS)
                    return@withLock
                }
            }

            val idle = candidate
            if (idle != null) {
                if (isUsable(idle)) return Lease(idle.client, key, wasReused = true)
                drop(key, idle.client)
                continue
            }

            if (reserved) {
                val client = clientFactory(config)
                try {
                    client.connect()
                } catch (e: Throwable) {
                    runCatching { client.close() }
                    lock.withLock {
                        buckets[key]?.let { it.total-- }
                        slotFreed.signalAll()
                    }
                    throw e
                }
                return Lease(client, key, wasReused = false)
            }
        }
    }

    /**
     * Runs [block] with a leased client. When [retryOnStale] is set and the failure happened on a client that had
     * been idle (the server may have dropped it), the operation is repeated once on a fresh connection; only use
     * that for operations that are safe to repeat.
     */
    fun <T> use(config: ConnectionConfig, retryOnStale: Boolean = false, block: (RemoteClient) -> T): T {
        var attempt = 0
        while (true) {
            val lease = acquire(config)
            try {
                val result = block(lease.client)
                lease.release()
                return result
            } catch (e: RemoteException) {
                if (isSemantic(e)) {
                    lease.release()
                    throw e
                }
                lease.discard()
                if (lease.wasReused && retryOnStale && attempt++ == 0) continue
                throw e
            } catch (e: IOException) {
                lease.discard()
                if (lease.wasReused && retryOnStale && attempt++ == 0) continue
                throw e
            } catch (e: Throwable) {
                lease.discard()
                throw e
            }
        }
    }

    /** Closes idle clients of a drive, e.g. after its settings changed or it was deleted. */
    fun invalidate(connectionId: String) {
        val victims = ArrayList<RemoteClient>()
        lock.withLock {
            buckets.entries.filter { it.key.startsWith("$connectionId#") }.forEach { (_, bucket) ->
                while (true) {
                    val idle = bucket.idle.pollFirst() ?: break
                    bucket.total--
                    victims.add(idle.client)
                }
            }
            slotFreed.signalAll()
        }
        victims.forEach { runCatching { it.close() } }
    }

    override fun close() {
        val victims = ArrayList<RemoteClient>()
        lock.withLock {
            closed = true
            buckets.values.forEach { bucket ->
                while (true) victims.add((bucket.idle.pollFirst() ?: break).client)
            }
            buckets.clear()
            slotFreed.signalAll()
        }
        evictor?.shutdownNow()
        victims.forEach { runCatching { it.close() } }
    }

    /** Closes clients that have been idle for too long. Called periodically; public for tests. */
    fun evictIdle() {
        val victims = ArrayList<RemoteClient>()
        val now = clock()
        lock.withLock {
            buckets.values.forEach { bucket ->
                val iterator = bucket.idle.iterator()
                while (iterator.hasNext()) {
                    val idle = iterator.next()
                    if (now - idle.since >= idleTimeoutMs) {
                        iterator.remove()
                        bucket.total--
                        victims.add(idle.client)
                    }
                }
            }
            if (victims.isNotEmpty()) slotFreed.signalAll()
        }
        victims.forEach { runCatching { it.close() } }
    }

    private fun isUsable(idle: Idle): Boolean {
        if (!idle.client.isConnected) return false
        return if (clock() - idle.since >= probeAfterIdleMs) idle.client.isHealthy() else true
    }

    private fun giveBack(key: String, client: RemoteClient) {
        var keep = false
        lock.withLock {
            val bucket = buckets[key]
            if (bucket != null && !closed && client.isConnected) {
                bucket.idle.addFirst(Idle(client, clock()))
                keep = true
            } else {
                bucket?.let { it.total-- }
            }
            slotFreed.signalAll()
        }
        if (!keep) runCatching { client.close() }
    }

    private fun drop(key: String, client: RemoteClient) {
        runCatching { client.close() }
        lock.withLock {
            buckets[key]?.let { it.total-- }
            slotFreed.signalAll()
        }
    }

    private fun keyOf(config: ConnectionConfig) = "${config.connection.id}#${config.hashCode()}"

    /** Errors that say something about the request, not about the health of the connection. */
    private fun isSemantic(e: RemoteException) = e is RemoteNotFoundException ||
        e is RemoteAlreadyExistsException ||
        e is RemoteAccessDeniedException ||
        e is RemoteNotEmptyException ||
        e is UnsupportedRemoteOperationException ||
        e is OperationCancelledException ||
        e is AuthenticationFailedException ||
        e is UntrustedServerException ||
        e is MissingCredentialsException

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
        const val DEFAULT_PROBE_AFTER_IDLE_MS = 5_000L
        const val DEFAULT_ACQUIRE_TIMEOUT_MS = 60_000L
        const val DEFAULT_EVICTION_INTERVAL_MS = 15_000L

        fun defaultMaxClients(protocol: Protocol) = when (protocol) {
            Protocol.SMB, Protocol.SFTP -> 4
            Protocol.FTP, Protocol.FTPES, Protocol.FTPS -> 3
        }
    }
}
