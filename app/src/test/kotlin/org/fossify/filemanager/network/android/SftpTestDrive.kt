package org.fossify.filemanager.network.android

import android.content.Context
import org.fossify.filemanager.network.core.ConnectionSecrets
import org.fossify.filemanager.network.core.EmbeddedSftpServer
import org.fossify.filemanager.network.core.KeySecretCipher
import org.fossify.filemanager.network.core.NetworkConnection
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.NetworkManager
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** A saved network drive backed by an embedded SFTP server, for tests that exercise the Android glue. */
class SftpTestDrive(context: Context, folder: TemporaryFolder) {
    val serverRoot: File = folder.newFolder("sftp-root")
    private val server = EmbeddedSftpServer(serverRoot, folder.newFile("hostkey.ser")).also { it.start() }
    val manager: NetworkManager
    val connection: NetworkConnection

    init {
        val identity = server.presentedIdentity()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        manager = NetworkManager.installForTesting(context, KeySecretCipher { key })
        connection = NetworkConnection(
            id = "drive1",
            name = "Test NAS",
            protocol = Protocol.SFTP,
            host = "127.0.0.1",
            port = server.port,
            username = EmbeddedSftpServer.USER,
            trustedIdentity = identity,
        )
        manager.repository.save(connection, ConnectionSecrets(password = EmbeddedSftpServer.PASSWORD))
    }

    fun remote(path: String = "/") = RemotePath.build(connection.id, path)

    fun stop() {
        manager.pool.close()
        server.stop()
    }
}
