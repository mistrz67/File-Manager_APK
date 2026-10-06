package org.fossify.filemanager.network.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ClientPoolTest {
    private val config = ConnectionConfig(NetworkConnection("c1", "test", Protocol.SFTP, "host"), ConnectionSecrets(password = "pw"))
    private val created = ArrayList<InMemoryClient>()
    private val now = AtomicLong(0)

    private fun pool(max: Int = 2, acquireTimeoutMs: Long = 500) = ClientPool(
        clientFactory = { InMemoryClient(it.connection).also { client -> synchronized(created) { created.add(client) } } },
        maxClients = { max },
        evictionIntervalMs = 0,
        acquireTimeoutMs = acquireTimeoutMs,
        clock = { now.get() },
    )

    @Test
    fun reusesReleasedClients() {
        val pool = pool()
        val first = pool.acquire(config)
        val client = first.client
        assertFalse(first.wasReused)
        first.release()

        val second = pool.acquire(config)
        assertSame(client, second.client)
        assertTrue(second.wasReused)
        assertEquals(1, created.size)
        assertEquals(1, created[0].connectCount)
        second.release()
        pool.close()
    }

    @Test
    fun limitsParallelClientsAndWaitsForRelease() {
        val pool = pool(max = 1, acquireTimeoutMs = 5_000)
        val first = pool.acquire(config)
        val gotSecond = CountDownLatch(1)
        val thread = Thread {
            pool.acquire(config).release()
            gotSecond.countDown()
        }
        thread.start()

        assertFalse(gotSecond.await(150, TimeUnit.MILLISECONDS))
        first.release()
        assertTrue(gotSecond.await(2, TimeUnit.SECONDS))
        thread.join()
        assertEquals(1, created.size)
        pool.close()
    }

    @Test
    fun timesOutWhenAllClientsAreBusy() {
        val pool = pool(max = 1, acquireTimeoutMs = 100)
        // the injected clock does not advance, so use real time for the wait
        val busy = pool.acquire(config)
        val realClockPool = ClientPool(
            clientFactory = { InMemoryClient(it.connection) },
            maxClients = { 1 },
            evictionIntervalMs = 0,
            acquireTimeoutMs = 100,
        )
        val lease = realClockPool.acquire(config)
        try {
            realClockPool.acquire(config)
            fail("expected a timeout")
        } catch (expected: ConnectionFailedException) {
        }
        lease.release()
        busy.release()
        realClockPool.close()
        pool.close()
    }

    @Test
    fun discardedClientsAreClosedAndSlotIsFreed() {
        val pool = pool(max = 1)
        val lease = pool.acquire(config)
        val client = lease.client as InMemoryClient
        lease.discard()
        assertEquals(1, client.closeCount)

        val next = pool.acquire(config)
        assertNotSame(client, next.client)
        next.release()
        pool.close()
    }

    @Test
    fun brokenClientIsNotReturnedToTheIdleList() {
        val pool = pool()
        val lease = pool.acquire(config)
        (lease.client as InMemoryClient).connected = false
        lease.release()
        val next = pool.acquire(config)
        assertNotSame(lease.client, next.client)
        next.release()
        pool.close()
    }

    @Test
    fun idleClientsAreEvicted() {
        val pool = pool()
        pool.acquire(config).release()
        assertEquals(0, created[0].closeCount)

        now.set(ClientPool.DEFAULT_IDLE_TIMEOUT_MS + 1)
        pool.evictIdle()
        assertEquals(1, created[0].closeCount)

        val lease = pool.acquire(config)
        assertEquals(2, created.size)
        lease.release()
        pool.close()
    }

    @Test
    fun staleIdleClientsAreProbedAndReplaced() {
        val pool = pool()
        pool.acquire(config).release()
        created[0].healthy = false
        now.set(ClientPool.DEFAULT_PROBE_AFTER_IDLE_MS + 1)

        val lease = pool.acquire(config)
        assertEquals(2, created.size)
        assertFalse(lease.wasReused)
        assertEquals(1, created[0].closeCount)
        lease.release()
        pool.close()
    }

    @Test
    fun useRetriesOnceWhenAReusedClientFails() {
        val pool = pool()
        pool.use(config) { it.list("/") }
        created[0].beforeOperation = { throw ConnectionFailedException("connection reset") }

        val attempts = AtomicInteger()
        val result = pool.use(config, retryOnStale = true) {
            attempts.incrementAndGet()
            it.list("/")
        }
        assertTrue(result.isEmpty())
        assertEquals(2, attempts.get())
        assertEquals(2, created.size)
        pool.close()
    }

    @Test
    fun useDoesNotRetryByDefaultAndDiscardsTheClient() {
        val pool = pool()
        pool.use(config) { it.list("/") }
        created[0].beforeOperation = { throw ConnectionFailedException("connection reset") }
        try {
            pool.use(config) { it.list("/") }
            fail()
        } catch (expected: ConnectionFailedException) {
        }
        assertEquals(1, created[0].closeCount)
        pool.close()
    }

    @Test
    fun semanticErrorsKeepTheConnection() {
        val pool = pool()
        try {
            pool.use(config) { it.list("/missing") }
            fail()
        } catch (expected: RemoteNotFoundException) {
        }
        assertEquals(0, created[0].closeCount)
        pool.use(config) { it.list("/") }
        assertEquals(1, created.size)
        pool.close()
    }

    @Test
    fun connectFailureFreesTheSlot() {
        var failures = 1
        val pool = ClientPool(
            clientFactory = {
                object : RemoteClient by InMemoryClient(it.connection) {
                    override val isConnected = false
                    override fun connect() {
                        if (failures-- > 0) throw ConnectionFailedException("refused")
                    }
                }
            },
            maxClients = { 1 },
            evictionIntervalMs = 0,
            acquireTimeoutMs = 200,
        )
        try {
            pool.acquire(config)
            fail()
        } catch (expected: ConnectionFailedException) {
        }
        // the single slot must be available again
        pool.acquire(config).release()
        pool.close()
    }

    @Test
    fun changedConfigurationDoesNotReuseOldClients() {
        val pool = pool()
        pool.acquire(config).release()
        val changed = config.copy(secrets = ConnectionSecrets(password = "other"))
        val lease = pool.acquire(changed)
        assertFalse(lease.wasReused)
        assertEquals(2, created.size)
        lease.release()
        pool.close()
    }

    @Test
    fun invalidateClosesIdleClients() {
        val pool = pool()
        pool.acquire(config).release()
        pool.invalidate("c1")
        assertEquals(1, created[0].closeCount)
        pool.close()
    }
}
