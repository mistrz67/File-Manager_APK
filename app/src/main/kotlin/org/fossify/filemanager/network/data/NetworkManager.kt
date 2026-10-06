package org.fossify.filemanager.network.data

import android.content.Context
import org.fossify.filemanager.network.core.AuthMethod
import org.fossify.filemanager.network.core.ClientPool
import org.fossify.filemanager.network.core.ConnectionConfig
import org.fossify.filemanager.network.core.ConnectionSecrets
import org.fossify.filemanager.network.core.KeySecretCipher
import org.fossify.filemanager.network.core.LocalEndpoint
import org.fossify.filemanager.network.core.MissingCredentialsException
import org.fossify.filemanager.network.core.RemoteEndpoint
import org.fossify.filemanager.network.core.RemoteNotFoundException
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.core.TransferEngine

/** Process wide access to the saved drives, the connection pool and the transfer engine. */
class NetworkManager private constructor(context: Context) {
    val repository = ConnectionRepository(context, KeySecretCipher { KeystoreKey.getOrNull() })
    val pool = ClientPool()
    val engine = TransferEngine()
    val files = RemoteFiles(context.applicationContext, this)

    /** Configuration of a saved drive including its secrets. Throws [MissingCredentialsException] if they are gone. */
    fun config(connectionId: String): ConnectionConfig {
        val connection = repository.get(connectionId) ?: throw RemoteNotFoundException(RemotePath.build(connectionId, "/"))
        val secrets = repository.loadSecrets(connectionId)
        if (secrets == null && connection.authMethod != AuthMethod.ANONYMOUS) {
            throw MissingCredentialsException(connectionId)
        }
        return ConnectionConfig(connection, secrets ?: ConnectionSecrets.EMPTY)
    }

    fun remoteEndpoint(connectionId: String) = RemoteEndpoint(pool, config(connectionId))

    fun localEndpoint() = LocalEndpoint()

    fun endpointFor(path: String) = RemotePath.connectionId(path)?.let { remoteEndpoint(it) } ?: localEndpoint()

    /** Call after a drive was edited or removed so no stale connection is reused. */
    fun connectionChanged(connectionId: String) {
        pool.invalidate(connectionId)
    }

    fun connectionRemoved(connectionId: String) {
        pool.invalidate(connectionId)
        files.cache.clear(connectionId)
    }

    companion object {
        @Volatile
        private var instance: NetworkManager? = null

        fun get(context: Context): NetworkManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

val Context.networkManager: NetworkManager get() = NetworkManager.get(this)
