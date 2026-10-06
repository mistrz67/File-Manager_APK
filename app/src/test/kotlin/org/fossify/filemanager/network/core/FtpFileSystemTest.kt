package org.fossify.filemanager.network.core

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.Listener
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An embedded FTP server (Apache FtpServer) used by the FTP tests. */
class EmbeddedFtpServer(rootDir: java.io.File) {
    private val listener: Listener = ListenerFactory().apply { port = 0 }.createListener()
    private val server: FtpServer

    init {
        val userManager = PropertiesUserManagerFactory().createUserManager()
        userManager.save(
            BaseUser().apply {
                name = USER
                password = PASSWORD
                homeDirectory = rootDir.absolutePath
                authorities = listOf<Authority>(WritePermission())
            }
        )
        val factory = FtpServerFactory()
        factory.addListener("default", listener)
        factory.userManager = userManager
        server = factory.createServer()
    }

    val port: Int get() = listener.port

    fun start() = server.start()

    fun stop() = server.stop()

    fun config(password: String = PASSWORD, options: ClientOptions = ClientOptions()) = ConnectionConfig(
        NetworkConnection("ftp-test", "test", Protocol.FTP, "127.0.0.1", port, USER),
        ConnectionSecrets(password = password),
    ) to options

    companion object {
        const val USER = "tester"
        const val PASSWORD = "testpass"
    }
}

class FtpFileSystemTest : FileSystemContract() {
    companion object {
        @ClassRule
        @JvmField
        val folder = TemporaryFolder()

        private lateinit var server: EmbeddedFtpServer

        @BeforeClass
        @JvmStatic
        fun startServer() {
            server = EmbeddedFtpServer(folder.newFolder("ftp-root"))
            server.start()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() = server.stop()
    }

    override val root = "/"

    // Apache FtpServer lists modification times with second precision
    override val modifiedToleranceMs = 2_000L

    // the embedded server rejects backslashes in names
    override val allowsBackslashInNames = false

    override val supportsUnicodeNames: Boolean get() = jvmHandlesUnicodeFileNames

    override val listsHiddenFiles = false

    override fun openFileSystem(): FileSystem {
        val (config, options) = server.config()
        return FtpRemoteClient(config, options).also { it.connect() }
    }
}

/** Same server, but forcing the classic LIST command instead of MLSD. */
class FtpListCommandFileSystemTest : FileSystemContract() {
    companion object {
        @ClassRule
        @JvmField
        val folder = TemporaryFolder()

        private lateinit var server: EmbeddedFtpServer

        @BeforeClass
        @JvmStatic
        fun startServer() {
            server = EmbeddedFtpServer(folder.newFolder("ftp-root"))
            server.start()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() = server.stop()
    }

    override val root = "/"

    // LIST output carries only the date for files older than about six months
    override val modifiedToleranceMs = 24 * 3_600_000L

    override val allowsBackslashInNames = false

    override val supportsUnicodeNames: Boolean get() = jvmHandlesUnicodeFileNames

    override fun openFileSystem(): FileSystem {
        val (config, options) = server.config(options = ClientOptions(ftpUseMlsd = false))
        return FtpRemoteClient(config, options).also { it.connect() }
    }
}

class FtpAuthenticationTest {
    @get:org.junit.Rule
    val folder = TemporaryFolder()

    @Test
    fun wrongPasswordIsAnAuthenticationError() {
        val server = EmbeddedFtpServer(folder.newFolder())
        server.start()
        try {
            val (config, options) = server.config(password = "wrong")
            FtpRemoteClient(config, options).connect()
            fail()
        } catch (e: AuthenticationFailedException) {
            assertEquals(true, e.message?.startsWith("530"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun unreachableServerIsAConnectionError() {
        val server = EmbeddedFtpServer(folder.newFolder())
        server.start()
        val port = server.port
        server.stop()
        try {
            FtpRemoteClient(
                ConnectionConfig(NetworkConnection("x", "x", Protocol.FTP, "127.0.0.1", port, "u"), ConnectionSecrets(password = "p")),
                ClientOptions(connectTimeoutMs = 2_000),
            ).connect()
            fail()
        } catch (e: ConnectionFailedException) {
        }
    }
}
