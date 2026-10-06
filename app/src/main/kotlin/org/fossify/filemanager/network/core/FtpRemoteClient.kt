package org.fossify.filemanager.network.core

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPCmd
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

/** FTP, FTPS (implicit) and FTPES (explicit TLS) client built on Apache Commons Net. */
class FtpRemoteClient(
    private val config: ConnectionConfig,
    private val options: ClientOptions = ClientOptions(),
) : RemoteClient {
    override val connection: NetworkConnection get() = config.connection

    private var ftp: FTPClient? = null
    private var supportsMlsd = false

    /** Set when a data connection ended abnormally; the control connection is then in an unknown state. */
    @Volatile
    private var broken = false

    override val isConnected: Boolean get() = !broken && ftp?.isConnected == true

    override fun connect() {
        if (isConnected) return
        closeQuietly()
        broken = false
        val pinned = PinnedIdentity.parse(connection.trustedIdentity)
        try {
            connectOnce(pinned, options.ftpsProtocols)
        } catch (e: Exception) {
            closeQuietly()
            if (connection.protocol.usesTls && options.ftpsProtocols.isNotEmpty() && isProtocolMismatch(e)) {
                // the server may speak only TLS versions we did not offer first
                try {
                    connectOnce(pinned, emptyList())
                } catch (retry: Exception) {
                    closeQuietly()
                    throw mapConnectException(retry)
                }
            } else {
                throw mapConnectException(e)
            }
        }
    }

    private fun connectOnce(pinned: PinnedIdentity?, protocols: List<String>) {
        val client = createClient(pinned, protocols)
        ftp = client
        client.connect(connection.host, connection.effectivePort)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw ConnectionFailedException("Server refused the connection: ${client.replyString.trim()}")
        }

        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }

        login(client)
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (connection.ftpPassive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
        supportsMlsd = options.ftpUseMlsd && runCatching { client.hasFeature(FTPCmd.MLST) }.getOrDefault(false)
    }

    private fun isProtocolMismatch(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is SSLProtocolException ||
            (it is SSLHandshakeException && (it.message.orEmpty().contains("protocol_version", true) ||
                it.message.orEmpty().contains("No appropriate protocol", true)))
    }

    private fun createClient(pinned: PinnedIdentity?, protocols: List<String>): FTPClient {
        val client: FTPClient = when (connection.protocol) {
            Protocol.FTPS, Protocol.FTPES -> {
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(PinningTrustManager(connection.host, pinned)), SecureRandom())
                SessionResumingFtpsClient(
                    isImplicit = connection.protocol == Protocol.FTPS,
                    sslContext = context,
                    controlHost = connection.host,
                    controlPort = connection.effectivePort,
                    dataTimeoutMs = options.readTimeoutMs,
                ).also { ftps ->
                    if (protocols.isNotEmpty()) ftps.setEnabledProtocols(protocols.toTypedArray())
                }
            }

            else -> FTPClient()
        }
        client.connectTimeout = options.connectTimeoutMs
        client.defaultTimeout = options.readTimeoutMs
        client.setDataTimeout(Duration.ofMillis(options.readTimeoutMs.toLong()))
        client.setControlKeepAliveTimeout(Duration.ofSeconds(KEEP_ALIVE_SECONDS))
        client.setControlKeepAliveReplyTimeout(Duration.ofSeconds(10))
        client.controlEncoding = connection.encoding
        if (connection.encoding.equals(NetworkConnection.DEFAULT_ENCODING, ignoreCase = true)) {
            client.setAutodetectUTF8(true)
        }
        return client
    }

    private fun login(client: FTPClient) {
        val (user, password) = if (connection.authMethod == AuthMethod.ANONYMOUS) {
            "anonymous" to "anonymous@"
        } else {
            connection.username to config.secrets.password
        }

        if (!client.login(user, password)) {
            throw AuthenticationFailedException(client.replyString.trim())
        }
    }

    override fun initialDirectory(): String {
        val configured = connection.initialPath.trim()
        if (configured.isNotEmpty()) return Paths.normalize(configured)
        return runCatching { ftp?.printWorkingDirectory() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { Paths.normalize(it) } ?: "/"
    }

    override fun isHealthy(): Boolean {
        val client = ftp ?: return false
        if (!isConnected) return false
        return try {
            client.sendNoOp()
        } catch (e: IOException) {
            broken = true
            false
        }
    }

    // region listing

    override fun list(path: String): List<RemoteEntry> {
        val normalized = Paths.normalize(path)
        return operation(normalized) { client ->
            val files = listFiles(client, normalized)
            if (files == null || (!FTPReply.isPositiveCompletion(client.replyCode) && files.isEmpty())) {
                throw mapReply(client, normalized)
            }

            val entries = files.filterNotNull()
                .filter { it.name != null && it.name != "." && it.name != ".." && it.type != FTPFile.UNKNOWN_TYPE }
                .map { toEntry(client, normalized, it) }
            if (entries.isEmpty() && !isDirectory(client, normalized)) {
                // some servers (vsftpd) answer "226 OK" with an empty listing for folders that do not exist
                throw RemoteNotFoundException(normalized)
            }
            entries
        }
    }

    /** MLSD when available; otherwise LIST, first asking for hidden files (`-a`) and falling back to plain LIST. */
    private fun listFiles(client: FTPClient, path: String): Array<FTPFile>? {
        if (supportsMlsd) return client.mlistDir(path)

        client.listHiddenFiles = true
        val withHidden = try {
            client.listFiles(path)
        } finally {
            client.listHiddenFiles = false
        }
        if (withHidden != null && withHidden.isNotEmpty() && FTPReply.isPositiveCompletion(client.replyCode)) {
            return withHidden
        }
        // the server may not understand "-a" (or the folder is empty); plain LIST is the safe answer
        return client.listFiles(path)
    }

    private fun toEntry(client: FTPClient, parent: String, file: FTPFile): RemoteEntry {
        // LIST output may contain the full path for some servers; keep only the last segment
        val name = file.name.substringAfterLast('/')
        val path = Paths.join(parent, name)
        var isDirectory = file.isDirectory
        val isSymlink = file.isSymbolicLink
        if (isSymlink) {
            isDirectory = isDirectory(client, path)
        }

        return RemoteEntry(
            name = name,
            path = path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else file.size.coerceAtLeast(0L),
            modified = file.timestampInstant?.toEpochMilli() ?: 0L,
            isSymlink = isSymlink,
        )
    }

    /**
     * Whether [path] is a folder, found out by entering it. The working directory is not relied upon anywhere else,
     * and some servers refuse to delete the folder a session is in, so go back to the root afterwards.
     */
    private fun isDirectory(client: FTPClient, path: String): Boolean {
        val isDirectory = client.changeWorkingDirectory(path)
        if (isDirectory) client.changeWorkingDirectory("/")
        return isDirectory
    }

    override fun stat(path: String): RemoteEntry? {
        val normalized = Paths.normalize(path)
        val parent = Paths.parent(normalized) ?: return RemoteEntry("", "/", true)
        val name = Paths.name(normalized)
        return try {
            list(parent).firstOrNull { it.name == name }
        } catch (e: RemoteNotFoundException) {
            null
        }
    }

    // endregion

    // region streams

    override fun openRead(path: String): InputStream {
        val normalized = Paths.normalize(path)
        val client = connectedClient()
        val stream = try {
            client.retrieveFileStream(normalized)
        } catch (e: IOException) {
            broken = true
            throw mapIoException(e)
        } ?: throw mapReply(client, normalized)

        return object : FilterInputStream(stream) {
            private var finished = false

            override fun close() {
                if (finished) return
                finished = true
                try {
                    super.close()
                } finally {
                    completeTransfer(client)
                }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val normalized = Paths.normalize(path)
        val client = connectedClient()
        val stream = try {
            client.storeFileStream(normalized)
        } catch (e: IOException) {
            broken = true
            throw mapIoException(e)
        } ?: throw mapReply(client, normalized)

        return object : FilterOutputStream(stream) {
            private var finished = false

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                if (finished) return
                finished = true
                try {
                    super.close()
                } finally {
                    completeTransfer(client, failOnError = true)
                }
            }
        }
    }

    private fun completeTransfer(client: FTPClient, failOnError: Boolean = false) {
        val ok = try {
            client.completePendingCommand()
        } catch (e: IOException) {
            broken = true
            false
        }

        if (!ok) {
            broken = true
            if (failOnError) throw RemoteException("Transfer failed: ${client.replyString.trim()}")
        }
    }

    // endregion

    // region mutations

    override fun mkdir(path: String) {
        val normalized = Paths.normalize(path)
        if (stat(normalized) != null) throw RemoteAlreadyExistsException(normalized)
        operation(normalized) { client ->
            if (!client.makeDirectory(normalized)) throw mapReply(client, normalized)
        }
    }

    override fun deleteFile(path: String) {
        val normalized = Paths.normalize(path)
        operation(normalized) { client ->
            if (!client.deleteFile(normalized)) throw mapReply(client, normalized)
        }
    }

    override fun deleteDirectory(path: String) {
        val normalized = Paths.normalize(path)
        operation(normalized) { client ->
            if (!client.removeDirectory(normalized)) throw mapReply(client, normalized)
        }
    }

    override fun rename(from: String, to: String) {
        val source = Paths.normalize(from)
        val destination = Paths.normalize(to)
        if (stat(destination) != null) throw RemoteAlreadyExistsException(destination)
        operation(source) { client ->
            if (!client.rename(source, destination)) throw mapReply(client, source)
        }
    }

    override fun setModified(path: String, millis: Long) {
        val normalized = Paths.normalize(path)
        try {
            operation(normalized) { client ->
                val value = MFMT_FORMAT.format(java.time.Instant.ofEpochMilli(millis))
                client.setModificationTime(normalized, value)
            }
        } catch (ignored: IOException) {
            // best effort, not every server supports MFMT
        }
    }

    // endregion

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        val client = ftp ?: return
        ftp = null
        try {
            if (client.isConnected) {
                if (!broken) runCatching { client.logout() }
                client.disconnect()
            }
        } catch (ignored: IOException) {
        }
    }

    // region helpers

    private fun connectedClient(): FTPClient {
        val client = ftp
        if (client == null || !client.isConnected || broken) throw ConnectionFailedException("Not connected")
        return client
    }

    private fun <T> operation(path: String, block: (FTPClient) -> T): T {
        val client = connectedClient()
        try {
            return block(client)
        } catch (e: IOException) {
            if (e !is RemoteException) broken = true
            throw mapIoException(e)
        }
    }

    private fun mapReply(client: FTPClient, path: String): IOException {
        val reply = client.replyString.trim()
        val lower = reply.lowercase()
        return when (client.replyCode) {
            FTPReply.NOT_LOGGED_IN, FTPReply.NEED_PASSWORD -> AuthenticationFailedException(reply)
            FTPReply.FILE_UNAVAILABLE -> when {
                "exist" in lower && "not" !in lower && "already" in lower -> RemoteAlreadyExistsException(path)
                "not found" in lower || "no such" in lower || "not exist" in lower || "does not exist" in lower ->
                    RemoteNotFoundException(path)

                "not empty" in lower -> RemoteNotEmptyException(path)
                // servers answer 550 for both missing files and permission problems; tell them apart
                else -> if (existsQuietly(path)) RemoteAccessDeniedException(path) else RemoteNotFoundException(path)
            }

            FTPReply.FILE_ACTION_NOT_TAKEN -> when {
                "non-existing" in lower || "not found" in lower || "no such" in lower || "not exist" in lower ->
                    RemoteNotFoundException(path)

                else -> if (existsQuietly(path)) RemoteException(reply) else RemoteNotFoundException(path)
            }

            FTPReply.STORAGE_ALLOCATION_EXCEEDED, FTPReply.FILE_NAME_NOT_ALLOWED -> RemoteAccessDeniedException(path)
            FTPReply.SERVICE_NOT_AVAILABLE, FTPReply.CANNOT_OPEN_DATA_CONNECTION, FTPReply.TRANSFER_ABORTED ->
                ConnectionFailedException(reply)

            else -> RemoteException(reply.ifEmpty { "FTP error ${client.replyCode}" })
        }
    }

    private fun existsQuietly(path: String): Boolean = try {
        stat(path) != null
    } catch (e: IOException) {
        true
    }

    private fun mapIoException(e: IOException): IOException {
        if (e is RemoteException) return e
        generateSequence<Throwable>(e) { it.cause }.forEach {
            if (it is PinningTrustManager.UntrustedServerCertificateException) return it.untrusted
        }
        return when (e) {
            is UnknownHostException -> ConnectionFailedException("Unknown host: ${connection.host}", e)
            is SSLException -> ConnectionFailedException("TLS error: ${e.message}", e)
            is SocketException -> ConnectionFailedException(e.message ?: "Connection error", e)
            else -> ConnectionFailedException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun mapConnectException(e: Exception): IOException {
        if (e is IOException) return mapIoException(e)
        return ConnectionFailedException(e.message ?: e.javaClass.simpleName, e)
    }

    // endregion

    private companion object {
        const val KEEP_ALIVE_SECONDS = 60L
        val MFMT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
