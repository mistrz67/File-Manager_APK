package org.fossify.filemanager.network.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val engine = TransferEngine(bufferSize = 8, progressIntervalMs = 0)
    private val remote = InMemoryClient()
    private val remoteEndpoint = InMemoryEndpoint("remote", remote)
    private val localEndpoint = LocalEndpoint()

    private fun local(vararg parts: String) = File(folder.root, parts.joinToString("/")).absolutePath

    private fun writeLocal(path: String, text: String, modified: Long? = null) {
        File(path).apply { parentFile.mkdirs(); writeText(text); modified?.let { setLastModified(it) } }
    }

    private fun job(
        mode: TransferMode,
        sources: List<String>,
        from: Endpoint,
        to: Endpoint,
        destination: String,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    ) = TransferJob(mode, sources, from, to, destination, policy)

    @Test
    fun uploadsFilesAndFolders() {
        writeLocal(local("src", "a.txt"), "alpha")
        writeLocal(local("src", "dir", "b.txt"), "bravo")
        writeLocal(local("src", "dir", "deep", "c.txt"), "charlie")
        File(local("src", "emptydir")).mkdirs()
        remote.mkdirs("/target")

        val result = engine.run(job(TransferMode.COPY, listOf(local("src", "a.txt"), local("src", "dir"), local("src", "emptydir")), localEndpoint, remoteEndpoint, "/target"))

        assertTrue(result.isSuccess)
        assertEquals(3, result.completed)
        assertEquals("alpha", remote.readText("/target/a.txt"))
        assertEquals("bravo", remote.readText("/target/dir/b.txt"))
        assertEquals("charlie", remote.readText("/target/dir/deep/c.txt"))
        assertTrue(remote.exists("/target/emptydir"))
        assertTrue(File(local("src", "a.txt")).exists())
    }

    @Test
    fun downloadsFiles() {
        remote.putFile("/docs/readme.md", "hello", modified = 1_500_000_000_000L)
        val result = engine.run(job(TransferMode.COPY, listOf("/docs/readme.md"), remoteEndpoint, localEndpoint, local("out")))

        assertTrue(result.isSuccess)
        val file = File(local("out", "readme.md"))
        assertEquals("hello", file.readText())
        assertEquals(1_500_000_000_000L, file.lastModified())
    }

    @Test
    fun createsMissingDestinationFolders() {
        writeLocal(local("a.txt"), "x")
        val result = engine.run(job(TransferMode.COPY, listOf(local("a.txt")), localEndpoint, remoteEndpoint, "/new/deep/folder"))
        assertTrue(result.isSuccess)
        assertEquals("x", remote.readText("/new/deep/folder/a.txt"))
    }

    @Test
    fun moveDeletesSourcesAfterUpload() {
        writeLocal(local("src", "dir", "b.txt"), "bravo")
        writeLocal(local("src", "a.txt"), "alpha")

        val result = engine.run(job(TransferMode.MOVE, listOf(local("src", "a.txt"), local("src", "dir")), localEndpoint, remoteEndpoint, "/t"))

        assertTrue(result.isSuccess)
        assertFalse(File(local("src", "a.txt")).exists())
        assertFalse(File(local("src", "dir")).exists())
        assertEquals("bravo", remote.readText("/t/dir/b.txt"))
    }

    @Test
    fun moveWithinOneDriveRenames() {
        remote.putFile("/a/file.txt", "data")
        remote.putFile("/a/sub/inner.txt", "inner")
        remote.mkdirs("/b")

        val result = engine.run(job(TransferMode.MOVE, listOf("/a/file.txt", "/a/sub"), remoteEndpoint, remoteEndpoint, "/b"))

        assertTrue(result.isSuccess)
        assertEquals(2, result.completed)
        assertEquals("data", remote.readText("/b/file.txt"))
        assertEquals("inner", remote.readText("/b/sub/inner.txt"))
        assertFalse(remote.exists("/a/file.txt"))
        assertFalse(remote.exists("/a/sub"))
    }

    @Test
    fun moveIntoSameFolderIsANoOp() {
        remote.putFile("/a/file.txt", "data")
        val result = engine.run(job(TransferMode.MOVE, listOf("/a/file.txt"), remoteEndpoint, remoteEndpoint, "/a"))
        assertEquals(1, result.skipped)
        assertEquals("data", remote.readText("/a/file.txt"))
    }

    @Test
    fun copyWithinOneDriveKeepsBothAndNamesCopy() {
        remote.putFile("/a/file.txt", "data")
        val result = engine.run(job(TransferMode.COPY, listOf("/a/file.txt"), remoteEndpoint, remoteEndpoint, "/a"))
        assertTrue(result.isSuccess)
        assertEquals("data", remote.readText("/a/file (1).txt"))
        assertEquals("data", remote.readText("/a/file.txt"))
    }

    @Test
    fun keepBothPicksFreeNames() {
        remote.putFile("/t/a.txt", "old")
        remote.putFile("/t/a (1).txt", "old1")
        writeLocal(local("a.txt"), "new")

        engine.run(job(TransferMode.COPY, listOf(local("a.txt")), localEndpoint, remoteEndpoint, "/t", ConflictPolicy.KEEP_BOTH))

        assertEquals("old", remote.readText("/t/a.txt"))
        assertEquals("old1", remote.readText("/t/a (1).txt"))
        assertEquals("new", remote.readText("/t/a (2).txt"))
    }

    @Test
    fun skipPolicyLeavesExistingItemsAndSourcesAlone() {
        remote.putFile("/t/a.txt", "old")
        writeLocal(local("a.txt"), "new")
        writeLocal(local("b.txt"), "bee")

        val result = engine.run(job(TransferMode.MOVE, listOf(local("a.txt"), local("b.txt")), localEndpoint, remoteEndpoint, "/t", ConflictPolicy.SKIP))

        assertEquals(1, result.skipped)
        assertEquals(1, result.completed)
        assertEquals("old", remote.readText("/t/a.txt"))
        assertTrue("a skipped item must not be deleted by a move", File(local("a.txt")).exists())
        assertFalse(File(local("b.txt")).exists())
    }

    @Test
    fun overwritePolicyReplacesFilesAndMergesFolders() {
        remote.putFile("/t/a.txt", "old")
        remote.putFile("/t/dir/keep.txt", "keep")
        remote.putFile("/t/dir/over.txt", "old")
        writeLocal(local("a.txt"), "new")
        writeLocal(local("dir", "over.txt"), "new")
        writeLocal(local("dir", "added.txt"), "added")

        val result = engine.run(job(TransferMode.COPY, listOf(local("a.txt"), local("dir")), localEndpoint, remoteEndpoint, "/t", ConflictPolicy.OVERWRITE))

        assertTrue(result.isSuccess)
        assertEquals("new", remote.readText("/t/a.txt"))
        assertEquals("keep", remote.readText("/t/dir/keep.txt"))
        assertEquals("new", remote.readText("/t/dir/over.txt"))
        assertEquals("added", remote.readText("/t/dir/added.txt"))
    }

    @Test
    fun cannotCopyAFolderIntoItself() {
        remote.putFile("/a/inner/file.txt", "x")
        val result = engine.run(job(TransferMode.COPY, listOf("/a"), remoteEndpoint, remoteEndpoint, "/a/inner"))
        assertEquals(1, result.failures.size)
        assertFalse(remote.exists("/a/inner/a"))
    }

    @Test
    fun missingSourceIsReportedAndOthersContinue() {
        writeLocal(local("ok.txt"), "ok")
        val result = engine.run(job(TransferMode.COPY, listOf(local("gone.txt"), local("ok.txt")), localEndpoint, remoteEndpoint, "/t"))
        assertEquals(1, result.failures.size)
        assertEquals(local("gone.txt"), result.failures[0].path)
        assertEquals("ok", remote.readText("/t/ok.txt"))
    }

    @Test
    fun cancellationStopsAndRemovesTheUnfinishedFile() {
        writeLocal(local("big.bin"), "x".repeat(10_000))
        val token = CancellationToken()
        var calls = 0
        val listener = object : TransferListener {
            override fun onProgress(progress: TransferProgress) {
                if (progress.bytesDone > 100 && ++calls == 1) token.cancel()
            }
        }

        val result = engine.run(job(TransferMode.MOVE, listOf(local("big.bin")), localEndpoint, remoteEndpoint, "/t"), token, listener)

        assertTrue(result.cancelled)
        assertNull("partial file must be removed", remote.stat("/t/big.bin"))
        assertTrue("a cancelled move must keep the source", File(local("big.bin")).exists())
    }

    @Test
    fun reportsProgressWithTotals() {
        writeLocal(local("a.txt"), "12345")
        writeLocal(local("b.txt"), "1234567890")
        val seen = ArrayList<TransferProgress>()
        engine.run(job(TransferMode.COPY, listOf(local("a.txt"), local("b.txt")), localEndpoint, remoteEndpoint, "/t"), CancellationToken(), object : TransferListener {
            override fun onProgress(progress: TransferProgress) { seen.add(progress) }
        })

        val last = seen.last()
        assertEquals(15L, last.bytesTotal)
        assertEquals(15L, last.bytesDone)
        assertEquals(2, last.filesTotal)
        assertEquals(2, last.filesDone)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone })
    }

    @Test
    fun retriesAFileAfterADroppedConnection() {
        remote.putFile("/src/file.bin", "0123456789abcdefghijklmnopqrstuvwxyz")
        remote.failTransfers = 1
        val result = engine.run(job(TransferMode.COPY, listOf("/src/file.bin"), remoteEndpoint, localEndpoint, local("out")))
        assertTrue(result.failures.toString(), result.isSuccess)
        assertEquals("0123456789abcdefghijklmnopqrstuvwxyz", File(local("out", "file.bin")).readText())
    }

    @Test
    fun givesUpAfterRepeatedConnectionFailures() {
        remote.putFile("/src/file.bin", "0123456789abcdefghijklmnopqrstuvwxyz")
        remote.failTransfers = 5
        val result = engine.run(job(TransferMode.COPY, listOf("/src/file.bin"), remoteEndpoint, localEndpoint, local("out")))
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].error is ConnectionFailedException)
        assertFalse(File(local("out", "file.bin")).exists())
    }

    @Test
    fun deleteRemovesTreesAndCountsFiles() {
        remote.putFile("/d/a.txt", "a")
        remote.putFile("/d/sub/b.txt", "b")
        remote.putFile("/keep.txt", "k")
        var last = 0
        val result = engine.delete(remoteEndpoint, listOf("/d"), CancellationToken(), object : TransferListener {
            override fun onProgress(progress: TransferProgress) { last = progress.filesDone }
        })
        assertTrue(result.isSuccess)
        assertEquals(2, result.completed)
        assertEquals(2, last)
        assertFalse(remote.exists("/d"))
        assertNotNull(remote.stat("/keep.txt"))
    }

    @Test
    fun measureSumsSizesRecursively() {
        remote.putFile("/m/a.txt", "12345")
        remote.putFile("/m/sub/b.txt", "123")
        val (size, files) = engine.measure(remoteEndpoint, listOf("/m"))
        assertEquals(8L, size)
        assertEquals(2, files)
    }

    @Test
    fun uniqueNameHandlesExtensionsAndFolders() {
        assertEquals("a (1).txt", TransferEngine.uniqueName("a.txt", false, setOf("a.txt")))
        assertEquals("a (2).txt", TransferEngine.uniqueName("a.txt", false, setOf("a.txt", "a (1).txt")))
        assertEquals("folder (1)", TransferEngine.uniqueName("folder", true, setOf("folder")))
        assertEquals("v1.2 (1)", TransferEngine.uniqueName("v1.2", true, setOf("v1.2")))
        assertEquals(".hidden (1)", TransferEngine.uniqueName(".hidden", false, setOf(".hidden")))
        assertEquals("archive.tar (1).gz", TransferEngine.uniqueName("archive.tar.gz", false, setOf("archive.tar.gz")))
    }
}
