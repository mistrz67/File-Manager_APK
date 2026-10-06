package org.fossify.filemanager.network.core

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** An embedded SFTP server (Apache MINA SSHD) used by the SFTP tests. */
class EmbeddedSftpServer(val rootDir: java.io.File, keyFile: java.io.File) {
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath()).apply { setAlgorithm("RSA") }
        setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        subsystemFactories = listOf(SftpSubsystemFactory())
        fileSystemFactory = VirtualFileSystemFactory(rootDir.toPath())
    }

    val port: Int get() = server.port

    fun start() = server.start()

    fun stop() = server.stop(true)

    fun connection(trusted: String = "") = NetworkConnection(
        "sftp-test", "test", Protocol.SFTP, "127.0.0.1", port, USER, AuthMethod.PASSWORD, trustedIdentity = trusted,
    )

    fun config(trusted: String = "", password: String = PASSWORD) =
        ConnectionConfig(connection(trusted), ConnectionSecrets(password = password))

    /** Trust on first use: returns the identity the server presents. */
    fun presentedIdentity(): String {
        try {
            SftpRemoteClient(config()).connect()
            fail("a server without a pinned identity must not be trusted implicitly")
        } catch (e: UntrustedServerException) {
            return e.presentedIdentity
        }
        error("unreachable")
    }

    companion object {
        const val USER = "tester"
        const val PASSWORD = "testpass"
    }
}

class SftpFileSystemTest : FileSystemContract() {
    companion object {
        @ClassRule
        @JvmField
        val folder = TemporaryFolder()

        private lateinit var server: EmbeddedSftpServer
        private lateinit var identity: String

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val root = folder.newFolder("sftp-root")
            server = EmbeddedSftpServer(root, folder.newFile("hostkey.ser"))
            server.start()
            identity = server.presentedIdentity()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() = server.stop()
    }

    override val root = "/"

    // the embedded server rejects backslashes in names
    override val allowsBackslashInNames = false

    override val supportsUnicodeNames: Boolean get() = jvmHandlesUnicodeFileNames

    override fun openFileSystem(): FileSystem = SftpRemoteClient(server.config(identity)).also { it.connect() }
}

class SftpHostKeyTest {
    @get:org.junit.Rule
    val folder = TemporaryFolder()

    private fun server(): EmbeddedSftpServer {
        return EmbeddedSftpServer(folder.newFolder(), folder.newFile()).also { it.start() }
    }

    @Test
    fun unknownServerIsReportedWithItsIdentity() {
        val server = server()
        try {
            val identity = server.presentedIdentity()
            assertTrue(identity, PinnedIdentity.parse(identity) != null)

            // the user trusts it: the connection now works and the identity is stable
            SftpRemoteClient(server.config(identity)).use { it.connect(); assertTrue(it.isConnected) }
            assertEquals(identity, server.presentedIdentity())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aDifferentServerOnTheSameAddressIsRejected() {
        val first = server()
        val trusted = first.presentedIdentity()
        first.stop()

        val impostor = server()
        try {
            SftpRemoteClient(impostor.config(trusted)).connect()
            fail("a changed host key must be rejected")
        } catch (e: UntrustedServerException) {
            assertTrue(e.isChanged)
            assertEquals(trusted, e.previousIdentity)
            assertFalse(e.presentedIdentity == trusted)
        } finally {
            impostor.stop()
        }
    }

    @Test
    fun wrongPasswordIsAnAuthenticationError() {
        val server = server()
        try {
            val identity = server.presentedIdentity()
            try {
                SftpRemoteClient(server.config(identity, password = "wrong")).connect()
                fail()
            } catch (e: AuthenticationFailedException) {
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun unreachableServerIsAConnectionError() {
        val server = server()
        val port = server.port
        server.stop()
        val config = ConnectionConfig(
            NetworkConnection("x", "x", Protocol.SFTP, "127.0.0.1", port, "u"), ConnectionSecrets(password = "p"),
        )
        try {
            SftpRemoteClient(config, ClientOptions(connectTimeoutMs = 2_000)).connect()
            fail()
        } catch (e: ConnectionFailedException) {
        }
    }

    @Test
    fun initialDirectoryDefaultsToTheServerHome() {
        val server = server()
        try {
            val identity = server.presentedIdentity()
            SftpRemoteClient(server.config(identity)).use {
                it.connect()
                assertNotNull(it.initialDirectory())
                assertNull(it.stat("/does/not/exist"))
            }
        } finally {
            server.stop()
        }
    }
}
