package org.fossify.filemanager.network.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 2/3 client built on smbj. When the connection has no share configured the root lists the server's
 * disk shares and the first path segment selects the share.
 */
class SmbRemoteClient(
    private val config: ConnectionConfig,
    private val options: ClientOptions = ClientOptions(),
) : RemoteClient {
    override val connection: NetworkConnection get() = config.connection

    private var client: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    override val isConnected: Boolean
        get() = smbConnection?.isConnected == true && session != null

    override fun connect() {
        if (isConnected) return
        closeQuietly()
        try {
            when (connection.authMethod) {
                AuthMethod.ANONYMOUS -> connectAnonymously()
                else -> connectWith(
                    AuthenticationContext(connection.username, config.secrets.password.toCharArray(), connection.domain)
                )
            }
        } catch (e: Exception) {
            closeQuietly()
            throw mapException(e, "/")
        }
    }

    private fun connectAnonymously() {
        try {
            connectWith(AuthenticationContext.guest())
        } catch (e: Exception) {
            closeQuietly()
            connectWith(AuthenticationContext.anonymous())
        }
    }

    private fun connectWith(context: AuthenticationContext) {
        val smbConfig = SmbConfig.builder()
            .withTimeout(options.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(options.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
        val newClient = SMBClient(smbConfig)
        client = newClient
        val newConnection = newClient.connect(connection.host, connection.effectivePort)
        smbConnection = newConnection
        session = newConnection.authenticate(context)
    }

    override fun initialDirectory(): String {
        val configured = connection.initialPath.trim()
        return if (configured.isEmpty()) "/" else Paths.normalize(configured)
    }

    override fun isHealthy(): Boolean {
        if (!isConnected) return false
        return try {
            shares.values.firstOrNull()?.folderExists("")
            true
        } catch (e: Exception) {
            false
        }
    }

    // region listing

    override fun list(path: String): List<RemoteEntry> {
        val normalized = Paths.normalize(path)
        try {
            if (needsShareName() && normalized == "/") {
                return listShares()
            }

            val target = resolve(normalized)
            return openShare(target.share).list(target.relative)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map {
                    val attributes = it.fileAttributes
                    RemoteEntry(
                        name = it.fileName,
                        path = Paths.join(normalized, it.fileName),
                        isDirectory = attributes.hasFlag(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                        size = it.endOfFile,
                        modified = it.lastWriteTime.toEpochMillis(),
                        isSymlink = attributes.hasFlag(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT),
                    )
                }
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    private fun listShares(): List<RemoteEntry> {
        val currentSession = session ?: throw ConnectionFailedException("Not connected")
        try {
            val service = ServerService(SMBTransportFactories.SRVSVC.getTransport(currentSession))
            return service.shares1
                .filter { (it.type and TYPE_MASK) == STYPE_DISKTREE && (it.type and STYPE_SPECIAL) == 0 && !it.netName.endsWith("$") }
                .map { RemoteEntry(it.netName, "/${it.netName}", true) }
        } catch (e: Exception) {
            throw sharesUnavailable(e)
        } catch (e: LinkageError) {
            // the share lookup library refers to java.rmi exceptions, which Android does not have
            throw sharesUnavailable(e)
        }
    }

    private fun sharesUnavailable(cause: Throwable) = RemoteException(
        "Could not list the shares of ${connection.host}. Enter the share name in the drive settings.",
        cause
    )

    override fun stat(path: String): RemoteEntry? {
        val normalized = Paths.normalize(path)
        try {
            if (needsShareName() && normalized == "/") {
                return RemoteEntry("", "/", true)
            }

            val target = resolve(normalized)
            if (target.relative.isEmpty()) {
                openShare(target.share)
                return RemoteEntry(target.share.ifEmpty { "" }, normalized, true)
            }

            val share = openShare(target.share)
            val info = try {
                share.getFileInformation(target.relative)
            } catch (e: SMBApiException) {
                if (isNotFound(e)) return null
                throw e
            }
            return RemoteEntry(
                name = Paths.name(normalized),
                path = normalized,
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                modified = info.basicInformation.lastWriteTime.toEpochMillis(),
                isSymlink = info.basicInformation.fileAttributes.hasFlag(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT),
            )
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    // endregion

    // region streams

    override fun openRead(path: String): InputStream {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            val file = openShare(target.share).openFile(
                target.relative,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            return object : FilterInputStream(file.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    override fun openWrite(path: String): OutputStream {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            val file = openShare(target.share).openFile(
                target.relative,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            return object : FilterOutputStream(file.outputStream) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    // endregion

    // region mutations

    override fun mkdir(path: String) {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            if (target.relative.isEmpty()) throw UnsupportedRemoteOperationException("Cannot create shares")
            val share = openShare(target.share)
            if (share.folderExists(target.relative) || share.fileExists(target.relative)) {
                throw RemoteAlreadyExistsException(normalized)
            }
            share.mkdir(target.relative)
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    override fun deleteFile(path: String) {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            openShare(target.share).rm(target.relative)
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    override fun deleteDirectory(path: String) {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            openShare(target.share).rmdir(target.relative, false)
        } catch (e: Exception) {
            throw mapException(e, normalized)
        }
    }

    override fun rename(from: String, to: String) {
        val source = Paths.normalize(from)
        val destination = Paths.normalize(to)
        try {
            val sourceTarget = resolve(source)
            val destinationTarget = resolve(destination)
            if (sourceTarget.share != destinationTarget.share) {
                throw UnsupportedRemoteOperationException("Cannot rename across shares")
            }

            val share = openShare(sourceTarget.share)
            if (share.fileExists(destinationTarget.relative) || share.folderExists(destinationTarget.relative)) {
                throw RemoteAlreadyExistsException(destination)
            }

            val entry: DiskEntry = share.open(
                sourceTarget.relative,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            entry.use { it.rename(destinationTarget.relative, false) }
        } catch (e: Exception) {
            throw mapException(e, source)
        }
    }

    override fun setModified(path: String, millis: Long) {
        val normalized = Paths.normalize(path)
        try {
            val target = resolve(normalized)
            val entry = openShare(target.share).open(
                target.relative,
                EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            entry.use {
                it.setFileInformation(
                    FileBasicInformation(
                        FileBasicInformation.DONT_SET,
                        FileBasicInformation.DONT_SET,
                        FileTime.ofEpochMillis(millis),
                        FileBasicInformation.DONT_SET,
                        0L,
                    )
                )
            }
        } catch (ignored: Exception) {
            // best effort
        }
    }

    // endregion

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { smbConnection?.close() }
        runCatching { client?.close() }
        session = null
        smbConnection = null
        client = null
    }

    // region helpers

    private class Target(val share: String, val relative: String)

    private fun needsShareName() = connection.share.isBlank()

    /** Splits an absolute drive path into the SMB share and the path (with backslashes) inside it. */
    private fun resolve(path: String): Target {
        val segments = Paths.segments(path)
        return if (needsShareName()) {
            if (segments.isEmpty()) throw UnsupportedRemoteOperationException("No share selected")
            Target(segments.first(), segments.drop(1).joinToString("\\"))
        } else {
            Target(connection.share.trim('/', '\\'), segments.joinToString("\\"))
        }
    }

    private fun openShare(name: String): DiskShare {
        shares[name]?.let { if (it.isConnected) return it else shares.remove(name) }
        val currentSession = session ?: throw ConnectionFailedException("Not connected")
        val share = currentSession.connectShare(name) as? DiskShare
            ?: throw RemoteException("$name is not a file share")
        shares[name] = share
        return share
    }

    private fun Long.hasFlag(flag: FileAttributes) = (this and flag.value) != 0L

    private fun isNotFound(e: SMBApiException) = e.status in NOT_FOUND_STATUSES

    private fun mapException(e: Exception, path: String): IOException {
        return when (e) {
            is RemoteException -> e
            is SMBApiException -> when (e.status) {
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED ->
                    AuthenticationFailedException(e.message, e)

                NtStatus.STATUS_ACCESS_DENIED -> RemoteAccessDeniedException(path, e)
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> RemoteAlreadyExistsException(path, e)
                NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> RemoteNotEmptyException(path, e)
                in NOT_FOUND_STATUSES -> RemoteNotFoundException(path, e)
                else -> RemoteException("${e.status}: ${e.message}", e)
            }

            is IOException -> ConnectionFailedException(e.message ?: e.javaClass.simpleName, e)
            else -> {
                // smbj wraps transport and protocol errors into its own runtime exceptions
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                if (root is IOException) {
                    ConnectionFailedException(root.message ?: root.javaClass.simpleName, e)
                } else {
                    RemoteException(e.message ?: e.javaClass.simpleName, e)
                }
            }
        }
    }

    // endregion

    private companion object {
        const val TYPE_MASK = 0xFFFF
        const val STYPE_DISKTREE = 0
        const val STYPE_SPECIAL = 0x80000000.toInt()

        val NOT_FOUND_STATUSES = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_BAD_NETWORK_NAME,
        )
    }
}
