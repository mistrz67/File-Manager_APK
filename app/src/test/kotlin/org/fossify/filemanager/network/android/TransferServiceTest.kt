package org.fossify.filemanager.network.android

import android.os.Looper
import org.fossify.filemanager.network.core.ConflictPolicy
import org.fossify.filemanager.network.core.TransferResult
import org.fossify.filemanager.network.transfer.TransferEvents
import org.fossify.filemanager.network.transfer.TransferQueue
import org.fossify.filemanager.network.transfer.TransferRequest
import org.fossify.filemanager.network.transfer.TransferService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives the background transfer service the way the screens do: enqueue a request, start the service, wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var drive: SftpTestDrive
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TransferQueue.clear()
        drive = SftpTestDrive(context, folder)
    }

    @After
    fun tearDown() {
        TransferQueue.clear()
        drive.stop()
    }

    private fun run(request: TransferRequest): TransferResult {
        val done = CountDownLatch(1)
        var result: TransferResult? = null
        val listener = TransferEvents.Listener { finished, outcome ->
            if (finished.id == request.id) {
                result = outcome
                done.countDown()
            }
        }
        TransferEvents.register(listener)
        try {
            TransferQueue.enqueue(context, request)
            val started = shadowOf(context).nextStartedService
            assertEquals(TransferService::class.java.name, started.component?.className)
            Robolectric.buildService(TransferService::class.java, started).create().startCommand(0, 1)

            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!done.await(25, TimeUnit.MILLISECONDS)) {
                shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > deadline) fail("the transfer did not finish in time")
            }
            return result!!
        } finally {
            TransferEvents.unregister(listener)
        }
    }

    private fun local(name: String) = folder.root.resolve(name)

    @Test
    fun copiesFilesAndFoldersToTheDrive() {
        val source = folder.newFolder("photos")
        source.resolve("a.txt").writeText("alpha")
        source.resolve("sub").mkdirs()
        source.resolve("sub/b.txt").writeText("beta")

        val result = run(TransferRequest(TransferRequest.Kind.COPY, listOf(source.path), drive.remote("/")))

        assertTrue(result.failures.toString(), result.isSuccess)
        assertEquals(2, result.completed)
        assertEquals("alpha", drive.serverRoot.resolve("photos/a.txt").readText())
        assertEquals("beta", drive.serverRoot.resolve("photos/sub/b.txt").readText())
        assertTrue("a copy leaves the source alone", source.resolve("a.txt").exists())
    }

    @Test
    fun movesFilesFromTheDriveToThePhone() {
        drive.serverRoot.resolve("report.txt").writeText("quarterly")
        val target = folder.newFolder("downloads")

        val result = run(
            TransferRequest(TransferRequest.Kind.MOVE, listOf(drive.remote("/report.txt")), target.path)
        )

        assertTrue(result.failures.toString(), result.isSuccess)
        assertEquals("quarterly", target.resolve("report.txt").readText())
        assertFalse("a move removes the source", drive.serverRoot.resolve("report.txt").exists())
    }

    @Test
    fun keepsBothFilesOnAConflict() {
        drive.serverRoot.resolve("same.txt").writeText("on the drive")
        val phoneFile = local("same.txt").apply { writeText("from the phone") }

        val result = run(
            TransferRequest(
                TransferRequest.Kind.COPY,
                listOf(phoneFile.path),
                drive.remote("/"),
                ConflictPolicy.KEEP_BOTH,
            )
        )

        assertTrue(result.failures.toString(), result.isSuccess)
        assertEquals("on the drive", drive.serverRoot.resolve("same.txt").readText())
        val copies = drive.serverRoot.list()!!.filter { it != "same.txt" }
        assertEquals(copies.toString(), 1, copies.size)
        assertEquals("from the phone", drive.serverRoot.resolve(copies.single()).readText())
    }

    @Test
    fun skipsFilesOnAConflictWhenAsked() {
        drive.serverRoot.resolve("same.txt").writeText("on the drive")
        val phoneFile = local("same.txt").apply { writeText("from the phone") }

        val result = run(
            TransferRequest(TransferRequest.Kind.COPY, listOf(phoneFile.path), drive.remote("/"), ConflictPolicy.SKIP)
        )

        assertEquals(1, result.skipped)
        assertEquals("on the drive", drive.serverRoot.resolve("same.txt").readText())
        assertEquals(1, drive.serverRoot.list()!!.size)
    }

    @Test
    fun deletesFoldersOnTheDrive() {
        drive.serverRoot.resolve("old/deep").mkdirs()
        drive.serverRoot.resolve("old/x.txt").writeText("x")
        drive.serverRoot.resolve("old/deep/y.txt").writeText("y")
        drive.serverRoot.resolve("keep.txt").writeText("keep")

        val result = run(TransferRequest(TransferRequest.Kind.DELETE, listOf(drive.remote("/old")), null))

        assertTrue(result.failures.toString(), result.isSuccess)
        assertFalse(drive.serverRoot.resolve("old").exists())
        assertTrue(drive.serverRoot.resolve("keep.txt").exists())
    }

    @Test
    fun reportsAFailureInsteadOfCrashing() {
        val result = run(
            TransferRequest(
                TransferRequest.Kind.COPY,
                listOf(drive.remote("/does-not-exist.txt")),
                folder.newFolder("target").path,
            )
        )

        assertFalse(result.isSuccess)
        assertTrue(result.failures.isNotEmpty())
    }

    @Test
    fun runsQueuedRequestsOneAfterAnother() {
        val first = local("one.txt").apply { writeText("1") }
        val second = local("two.txt").apply { writeText("2") }

        val firstResult = run(TransferRequest(TransferRequest.Kind.COPY, listOf(first.path), drive.remote("/")))
        val secondResult = run(TransferRequest(TransferRequest.Kind.COPY, listOf(second.path), drive.remote("/")))

        assertTrue(firstResult.isSuccess && secondResult.isSuccess)
        assertEquals(setOf("one.txt", "two.txt"), drive.serverRoot.list()!!.toSet())
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
