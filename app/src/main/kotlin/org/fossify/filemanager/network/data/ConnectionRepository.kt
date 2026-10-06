package org.fossify.filemanager.network.data

import android.content.Context
import org.fossify.filemanager.network.core.ConnectionConfig
import org.fossify.filemanager.network.core.ConnectionJson
import org.fossify.filemanager.network.core.ConnectionSecrets
import org.fossify.filemanager.network.core.NetworkConnection
import org.fossify.filemanager.network.core.SecretCipher

/**
 * Saved network drives. The description of a drive (host, user name, …) is kept in one preferences file; its
 * password and keys are encrypted with [SecretCipher] and kept in another one, which is excluded from backups.
 */
class ConnectionRepository(context: Context, private val cipher: SecretCipher) {
    private val metaPrefs = context.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    private val secretPrefs = context.applicationContext.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAll(): List<NetworkConnection> = synchronized(lock) {
        ConnectionJson.listFromJson(metaPrefs.getString(KEY_CONNECTIONS, null)).sortedBy { it.createdAt }
    }

    fun get(id: String): NetworkConnection? = getAll().firstOrNull { it.id == id }

    fun hasConnections(): Boolean = getAll().isNotEmpty()

    /**
     * Creates or updates a drive. [secrets] replaces the stored password/keys; pass null to keep what is stored.
     * Throws when the secrets cannot be encrypted, in which case nothing is changed.
     */
    fun save(connection: NetworkConnection, secrets: ConnectionSecrets?) {
        synchronized(lock) {
            val encrypted = secrets?.let { encrypt(connection.id, it) }
            val all = getAll().toMutableList()
            val index = all.indexOfFirst { it.id == connection.id }
            if (index >= 0) all[index] = connection else all.add(connection)
            metaPrefs.edit().putString(KEY_CONNECTIONS, ConnectionJson.listToJson(all)).commit()
            if (encrypted != null) secretPrefs.edit().putString(secretKey(connection.id), encrypted).commit()
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val remaining = getAll().filter { it.id != id }
            metaPrefs.edit().putString(KEY_CONNECTIONS, ConnectionJson.listToJson(remaining)).commit()
            secretPrefs.edit().remove(secretKey(id)).commit()
        }
    }

    /** Remembers which server identity the user trusted (SSH host key or TLS certificate). */
    fun setTrustedIdentity(id: String, identity: String) {
        synchronized(lock) {
            val connection = get(id) ?: return
            save(connection.copy(trustedIdentity = identity), null)
        }
    }

    /** Decrypted secrets, or null when none are stored or they cannot be decrypted any more. */
    fun loadSecrets(id: String): ConnectionSecrets? = synchronized(lock) {
        val blob = secretPrefs.getString(secretKey(id), null) ?: return null
        val plain = cipher.decrypt(blob, id.toByteArray()) ?: return null
        ConnectionJson.secretsFromJson(String(plain, Charsets.UTF_8))
    }

    fun hasUsableSecrets(id: String): Boolean = loadSecrets(id) != null

    fun config(id: String): ConnectionConfig? {
        val connection = get(id) ?: return null
        return ConnectionConfig(connection, loadSecrets(id) ?: ConnectionSecrets.EMPTY)
    }

    private fun encrypt(id: String, secrets: ConnectionSecrets): String =
        cipher.encrypt(ConnectionJson.secretsToJson(secrets).toByteArray(Charsets.UTF_8), id.toByteArray())

    private fun secretKey(id: String) = "secret_$id"

    companion object {
        const val META_PREFS = "network_drives"

        /** Must be excluded from backups, see res/xml/backup_rules.xml. */
        const val SECRET_PREFS = "network_drive_secrets"

        private const val KEY_CONNECTIONS = "connections"
    }
}
