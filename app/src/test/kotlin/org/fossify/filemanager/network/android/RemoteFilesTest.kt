package org.fossify.filemanager.network.android

import org.fossify.filemanager.network.core.MissingCredentialsException
import org.fossify.filemanager.network.core.RemoteAlreadyExistsException
import org.fossify.filemanager.network.core.RemoteNotFoundException
import org.fossify.filemanager.network.core.UntrustedServerException
import org.fossify.filemanager.network.data.ConnectionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var drive: SftpTestDrive

    private val files get() = drive.manager.files

    @Before
    fun setUp() {
        drive = SftpTestDrive(RuntimeEnvironment.getApplication(), folder)
    }

    @After
    fun tearDown() = drive.stop()

    @Test
    fun listsFilesAndFolders() {
        drive.serverRoot.resolve("docs").mkdirs()
        drive.serverRoot.resolve("a.txt").writeText("alpha")

        val entries = files.list(drive.remote("/")).associateBy { it.name }
        assertEquals(setOf("docs", "a.txt"), entries.keys)
        assertTrue(entries.getValue("docs").isDirectory)
        assertEquals(5L, entries.getValue("a.txt").size)
    }

    @Test
    fun initialPathIsAValidRemotePath() {
        val start = files.initialPath("drive1")
        assertTrue(start, start.startsWith("remote://drive1"))
    }

    @Test
    fun createsRenamesAndWritesItems() {
        val folderPath = files.createFolder(drive.remote("/"), "new")
        assertTrue(drive.serverRoot.resolve("new").isDirectory)

        val filePath = files.createFile(folderPath, "note.txt")
        assertTrue(drive.serverRoot.resolve("new/note.txt").isFile)
        try {
            files.createFile(folderPath, "note.txt")
            fail()
        } catch (expected: RemoteAlreadyExistsException) {
        }

        files.writeBytes(filePath, "hello".toByteArray())
        assertEquals("hello", drive.serverRoot.resolve("new/note.txt").readText())

        val renamed = files.rename(filePath, "renamed.txt")
        assertEquals(drive.remote("/new/renamed.txt"), renamed)
        assertTrue(drive.serverRoot.resolve("new/renamed.txt").isFile)
        assertFalse(drive.serverRoot.resolve("new/note.txt").exists())
    }

    @Test
    fun downloadsToTheCacheAndReusesUpToDateCopies() {
        drive.serverRoot.resolve("report.txt").writeText("quarterly numbers")
        val remote = drive.remote("/report.txt")

        val first = files.download(remote)
        assertEquals("report.txt", first.name)
        assertEquals("quarterly numbers", first.readText())

        val marker = first.lastModified()
        first.setLastModified(marker) // unchanged; a re-download would rewrite the file
        val second = files.download(remote)
        assertEquals(first, second)
        assertEquals(marker, second.lastModified())

        // a changed file is fetched again (the freshness check compares size and modification time)
        val changed = drive.serverRoot.resolve("report.txt")
        changed.writeText("changed numbers, now longer")
        changed.setLastModified(marker + 60_000)
        assertEquals("changed numbers, now longer", files.download(remote).readText())
    }

    @Test
    fun measuresFolders() {
        drive.serverRoot.resolve("m/sub").mkdirs()
        drive.serverRoot.resolve("m/a.txt").writeText("12345")
        drive.serverRoot.resolve("m/sub/b.txt").writeText("123")
        val (size, count) = files.measure(listOf(drive.remote("/m")))
        assertEquals(8L, size)
        assertEquals(2, count)
    }

    @Test
    fun missingFileIsReported() {
        try {
            files.download(drive.remote("/nope.txt"))
            fail()
        } catch (expected: RemoteNotFoundException) {
        }
    }

    @Test
    fun savedSecretsAreEncryptedAndSurviveARestart() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(ConnectionRepository.SECRET_PREFS, 0)
        val stored = prefs.getString("secret_drive1", null)
        assertNotNull(stored)
        assertTrue(stored!!, stored.startsWith("v1:"))
        assertFalse("the password must not be stored in clear text", stored.contains("testpass"))

        assertEquals("testpass", drive.manager.repository.loadSecrets("drive1")!!.password)
    }

    @Test
    fun missingSecretsAskForCredentials() {
        RuntimeEnvironment.getApplication().getSharedPreferences(ConnectionRepository.SECRET_PREFS, 0).edit().clear().commit()
        try {
            drive.manager.config("drive1")
            fail()
        } catch (expected: MissingCredentialsException) {
        }
    }

    @Test
    fun aServerWithoutTrustedIdentityIsRefused() {
        drive.manager.repository.setTrustedIdentity("drive1", "")
        drive.manager.connectionChanged("drive1")
        try {
            files.list(drive.remote("/"))
            fail()
        } catch (e: UntrustedServerException) {
            assertFalse(e.isChanged)
        }
    }

    @Test
    fun editingADriveKeepsItsSecretsWhenNoneAreGiven() {
        val updated = drive.connection.copy(name = "Renamed NAS")
        drive.manager.repository.save(updated, null)
        assertEquals("Renamed NAS", drive.manager.repository.get("drive1")!!.name)
        assertEquals("testpass", drive.manager.repository.loadSecrets("drive1")!!.password)
    }

    @Test
    fun deletingADriveRemovesItsSecretsAndCache() {
        drive.serverRoot.resolve("x.txt").writeText("x")
        val cached = files.download(drive.remote("/x.txt"))
        assertTrue(cached.exists())

        drive.manager.repository.delete("drive1")
        drive.manager.connectionRemoved("drive1")
        assertTrue(drive.manager.repository.getAll().isEmpty())
        assertEquals(null, drive.manager.repository.loadSecrets("drive1"))
        assertFalse(cached.exists())
    }
}
