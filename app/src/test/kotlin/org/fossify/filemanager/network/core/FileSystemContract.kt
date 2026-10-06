package org.fossify.filemanager.network.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.UUID

/**
 * Behaviour every [FileSystem] implementation must share. Concrete tests provide a connected file system
 * and an existing, writable [root] directory.
 */
abstract class FileSystemContract {
    protected abstract fun openFileSystem(): FileSystem

    /** Existing writable directory inside the file system under test. */
    protected abstract val root: String

    /** Precision of modification times after [FileSystem.setModified]. */
    protected open val modifiedToleranceMs = 2_000L

    protected open val supportsSetModified = true

    /** Characters the file system cannot store in names (SMB forbids `*` and `?`). */
    protected open val forbiddenNameChars = ""

    protected lateinit var fs: FileSystem
    protected lateinit var dir: String

    @Before
    fun setUpContract() {
        fs = openFileSystem()
        dir = Paths.join(root, "contract-${UUID.randomUUID().toString().take(8)}")
        fs.mkdir(dir)
    }

    @After
    fun tearDownContract() {
        try {
            fs.deleteRecursively(dir)
        } finally {
            (fs as? AutoCloseable)?.close()
        }
    }

    protected fun write(path: String, data: ByteArray) {
        fs.openWrite(path).use { it.write(data) }
    }

    protected fun read(path: String): ByteArray = fs.openRead(path).use { it.readBytes() }

    @Test
    fun listsAndStatsEntries() {
        write(Paths.join(dir, "hello.txt"), "hello".toByteArray())
        fs.mkdir(Paths.join(dir, "folder"))

        val entries = fs.list(dir).associateBy { it.name }
        assertEquals(setOf("hello.txt", "folder"), entries.keys)
        assertFalse(entries.getValue("hello.txt").isDirectory)
        assertEquals(5L, entries.getValue("hello.txt").size)
        assertTrue(entries.getValue("folder").isDirectory)
        assertEquals(Paths.join(dir, "hello.txt"), entries.getValue("hello.txt").path)

        val stat = fs.stat(Paths.join(dir, "hello.txt"))
        assertNotNull(stat)
        assertEquals(5L, stat!!.size)
        assertTrue(fs.stat(Paths.join(dir, "folder"))!!.isDirectory)
        assertNull(fs.stat(Paths.join(dir, "missing")))
    }

    @Test
    fun roundTripsBinaryData() {
        val data = ByteArray(3 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
        val path = Paths.join(dir, "binary.bin")
        write(path, data)
        assertEquals(data.size.toLong(), fs.stat(path)!!.size)
        assertArrayEquals(data, read(path))
    }

    @Test
    fun overwritingTruncates() {
        val path = Paths.join(dir, "file.txt")
        write(path, "a much longer first version".toByteArray())
        write(path, "short".toByteArray())
        assertEquals("short", String(read(path)))
    }

    @Test
    fun emptyFilesWork() {
        val path = Paths.join(dir, "empty")
        write(path, ByteArray(0))
        assertEquals(0L, fs.stat(path)!!.size)
        assertEquals(0, read(path).size)
    }

    @Test
    fun mkdirOnExistingNameFails() {
        val path = Paths.join(dir, "folder")
        fs.mkdir(path)
        try {
            fs.mkdir(path)
            fail("expected RemoteAlreadyExistsException")
        } catch (expected: RemoteAlreadyExistsException) {
        }
    }

    @Test
    fun renamesFilesAndFolders() {
        write(Paths.join(dir, "old.txt"), "x".toByteArray())
        fs.mkdir(Paths.join(dir, "oldDir"))
        write(Paths.join(dir, "oldDir", "inner.txt"), "inner".toByteArray())

        fs.rename(Paths.join(dir, "old.txt"), Paths.join(dir, "new.txt"))
        fs.rename(Paths.join(dir, "oldDir"), Paths.join(dir, "newDir"))

        assertNull(fs.stat(Paths.join(dir, "old.txt")))
        assertEquals("x", String(read(Paths.join(dir, "new.txt"))))
        assertNull(fs.stat(Paths.join(dir, "oldDir")))
        assertEquals("inner", String(read(Paths.join(dir, "newDir", "inner.txt"))))
    }

    @Test
    fun renameOntoExistingFails() {
        write(Paths.join(dir, "a.txt"), "a".toByteArray())
        write(Paths.join(dir, "b.txt"), "b".toByteArray())
        try {
            fs.rename(Paths.join(dir, "a.txt"), Paths.join(dir, "b.txt"))
            fail("expected RemoteAlreadyExistsException")
        } catch (expected: RemoteAlreadyExistsException) {
        }
        assertEquals("b", String(read(Paths.join(dir, "b.txt"))))
    }

    @Test
    fun deletesFilesAndTrees() {
        write(Paths.join(dir, "file.txt"), "x".toByteArray())
        fs.deleteFile(Paths.join(dir, "file.txt"))
        assertNull(fs.stat(Paths.join(dir, "file.txt")))

        val tree = Paths.join(dir, "tree")
        fs.mkdir(tree)
        fs.mkdir(Paths.join(tree, "sub"))
        write(Paths.join(tree, "sub", "leaf.txt"), "leaf".toByteArray())
        write(Paths.join(tree, "top.txt"), "top".toByteArray())
        fs.deleteRecursively(tree)
        assertNull(fs.stat(tree))
    }

    @Test
    fun handlesUnusualFileNames() {
        val names = listOf(
            "with space.txt",
            "[brackets].txt",
            "star*name.txt",
            "question?.txt",
            "hash#percent%20.txt",
            "zażółć gęślą jaźń.txt",
            "apostrophe's.txt",
            "back\\slash.txt".takeIf { allowsBackslashInNames },
            ".hidden",
        ).filterNotNull().filter { name -> name.none { it in forbiddenNameChars } }

        names.forEach { write(Paths.join(dir, it), it.toByteArray()) }
        val listed = fs.list(dir).map { it.name }.toSet()
        assertEquals(names.toSet(), listed)
        names.forEach {
            assertEquals(it, String(read(Paths.join(dir, it))))
            assertNotNull("stat of $it", fs.stat(Paths.join(dir, it)))
        }

        fs.rename(Paths.join(dir, "[brackets].txt"), Paths.join(dir, "renamed [x].txt"))
        assertEquals("[brackets].txt", String(read(Paths.join(dir, "renamed [x].txt"))))
        // deleting a name that looks like a wildcard pattern must remove exactly that file
        val victim = names.first { it == "star*name.txt" || it == "hash#percent%20.txt" }
        fs.deleteFile(Paths.join(dir, victim))
        assertEquals(names.size - 1, fs.list(dir).size)
    }

    protected open val allowsBackslashInNames = true

    @Test
    fun readingMissingFileFails() {
        try {
            fs.openRead(Paths.join(dir, "missing.txt")).use { it.readBytes() }
            fail("expected an exception")
        } catch (expected: RemoteNotFoundException) {
        }
    }

    @Test
    fun listingMissingDirectoryFails() {
        try {
            fs.list(Paths.join(dir, "missing"))
            fail("expected RemoteNotFoundException")
        } catch (expected: RemoteNotFoundException) {
        }
    }

    @Test
    fun setsModificationTime() {
        if (!supportsSetModified) return
        val path = Paths.join(dir, "dated.txt")
        write(path, "x".toByteArray())
        val wanted = 1_600_000_000_000L
        fs.setModified(path, wanted)
        val actual = fs.stat(path)!!.modified
        assertTrue("modified $actual should be close to $wanted", Math.abs(actual - wanted) <= modifiedToleranceMs)
    }

    @Test
    fun emptyDirectoryListsNothing() {
        assertTrue(fs.list(dir).isEmpty())
    }
}
