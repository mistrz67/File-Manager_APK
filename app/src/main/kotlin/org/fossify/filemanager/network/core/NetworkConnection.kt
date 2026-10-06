package org.fossify.filemanager.network.core

import java.util.UUID

/**
 * A saved network drive. Contains only non-secret data; passwords and private keys live in
 * [ConnectionSecrets] and are stored separately (encrypted).
 */
data class NetworkConnection(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    /** 0 means "use the default port of the protocol". */
    val port: Int = 0,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** SMB only: authentication domain / workgroup. */
    val domain: String = "",
    /** SMB only: share name. When blank, the root lists all available shares. */
    val share: String = "",
    /** Folder opened when the drive is selected. Blank means the default one for the server. */
    val initialPath: String = "",
    /** FTP only: use passive mode for data connections. */
    val ftpPassive: Boolean = true,
    /** FTP only: character set of file names. */
    val encoding: String = DEFAULT_ENCODING,
    /** Pinned server identity, see [PinnedIdentity]. Blank until the user trusts the server. */
    val trustedIdentity: String = "",
    val createdAt: Long = 0L,
) {
    val effectivePort: Int get() = if (port > 0) port else protocol.defaultPort

    /** Human readable address used in lists, e.g. `smb://nas/Public`. */
    fun displayAddress(): String {
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = if (port > 0 && port != protocol.defaultPort) ":$port" else ""
        val sharePart = if (protocol == Protocol.SMB && share.isNotBlank()) "/${share.trim('/')}" else ""
        return "${protocol.urlScheme}://$hostPart$portPart$sharePart"
    }

    companion object {
        const val DEFAULT_ENCODING = "UTF-8"

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

/** Secret part of a connection. Never log it; [toString] is intentionally redacted. */
data class ConnectionSecrets(
    val password: String = "",
    /** Private key in PEM/OpenSSH text form (SFTP key authentication). */
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val isEmpty: Boolean get() = password.isEmpty() && privateKey.isEmpty() && passphrase.isEmpty()

    override fun toString() = "ConnectionSecrets(***)"

    companion object {
        val EMPTY = ConnectionSecrets()
    }
}

data class ConnectionConfig(val connection: NetworkConnection, val secrets: ConnectionSecrets = ConnectionSecrets.EMPTY) {
    override fun toString() = "ConnectionConfig(${connection.id}, ${connection.protocol})"
}

/** Network timeouts and tuning shared by all protocol clients. */
data class ClientOptions(
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    /**
     * TLS versions offered by FTPS. TLS 1.2 is the default because session resumption on data connections, which
     * many FTPS servers require, is reliable there but not with every TLS 1.3 stack.
     */
    val ftpsProtocols: List<String> = listOf("TLSv1.2"),
    /** FTP: prefer MLSD over LIST when the server supports it. */
    val ftpUseMlsd: Boolean = true,
)
