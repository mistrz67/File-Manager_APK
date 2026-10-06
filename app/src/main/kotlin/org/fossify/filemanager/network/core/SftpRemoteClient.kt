package org.fossify.filemanager.network.core

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.UnknownHostException

/**
 * SFTP client built on JSch (maintained fork by mwiede). Host keys are never accepted implicitly: the
 * connection must carry a [NetworkConnection.trustedIdentity]; otherwise [UntrustedServerException] is thrown
 * with the key the server presented so the UI can ask the user.
 */
class SftpRemoteClient(
    private val config: ConnectionConfig,
    private val options: ClientOptions = ClientOptions(),
) : RemoteClient {
    override val connection: NetworkConnection get() = config.connection

    private var session: com.jcraft.jsch.Session? = null
    private var channel: ChannelSftp? = null
    private val pinned = PinnedIdentity.parse(connection.trustedIdentity)
    private val hostKeys = PinnedHostKeyRepository(pinned)

    override val isConnected: Boolean
        get() = session?.isConnected == true && channel?.let { it.isConnected && !it.isClosed } == true

    override fun connect() {
        if (isConnected) return
        closeQuietly()
        JschSetup.ensureInitialized()
        try {
            val jsch = JSch()
            jsch.hostKeyRepository = hostKeys
            if (connection.authMethod == AuthMethod.PRIVATE_KEY) {
                addIdentity(jsch)
            }

            val newSession = jsch.getSession(connection.username.ifEmpty { "anonymous" }, connection.host, connection.effectivePort)
            session = newSession
            newSession.setConfig("StrictHostKeyChecking", "yes")
            newSession.setConfig("PreferredAuthentications", preferredAuthentications())
            // one attempt per method: repeating a wrong password only gets the account locked or the client banned
            newSession.setConfig("NumberOfPasswordPrompts", "1")
            if (pinned != null) {
                newSession.setConfig("server_host_key", hostKeyAlgorithmsFor(pinned.type))
            }
            if (connection.authMethod == AuthMethod.PASSWORD) {
                newSession.setPassword(config.secrets.password.toByteArray(Charsets.UTF_8))
            }
            newSession.userInfo = CredentialsUserInfo(config.secrets.password, config.secrets.passphrase)
            newSession.setTimeout(options.readTimeoutMs)
            newSession.setServerAliveInterval(30_000)
            newSession.setServerAliveCountMax(3)
            newSession.connect(options.connectTimeoutMs)

            val newChannel = newSession.openChannel("sftp") as ChannelSftp
            newChannel.connect(options.connectTimeoutMs)
            channel = newChannel
        } catch (e: Exception) {
            closeQuietly()
            throw mapConnectException(e)
        }
    }

    private fun addIdentity(jsch: JSch) {
        val key = config.secrets.privateKey
        if (key.isBlank()) throw MissingCredentialsException(connection.id)
        val passphrase = config.secrets.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        try {
            jsch.addIdentity("network-drive-key", key.toByteArray(Charsets.UTF_8), null, passphrase)
        } catch (e: JSchException) {
            throw AuthenticationFailedException("Invalid private key or passphrase", e)
        }
    }

    private fun preferredAuthentications() = when (connection.authMethod) {
        AuthMethod.PRIVATE_KEY -> "publickey"
        else -> "password,keyboard-interactive"
    }

    private fun hostKeyAlgorithmsFor(keyType: String) = when (keyType) {
        "ssh-rsa" -> "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
        else -> keyType
    }

    override fun initialDirectory(): String {
        val configured = connection.initialPath.trim()
        if (configured.isNotEmpty()) return Paths.normalize(configured)
        return try {
            channel?.home?.takeIf { it.isNotEmpty() }?.let { Paths.normalize(it) } ?: "/"
        } catch (e: SftpException) {
            "/"
        }
    }

    // region listing

    override fun list(path: String): List<RemoteEntry> {
        val normalized = Paths.normalize(path)
        return operation(normalized) { sftp ->
            val result = ArrayList<RemoteEntry>()
            for (entry in sftp.ls(escape(normalized))) {
                val name = entry.filename
                if (name == "." || name == "..") continue
                result.add(toEntry(sftp, normalized, name, entry.attrs))
            }
            result
        }
    }

    override fun stat(path: String): RemoteEntry? {
        val normalized = Paths.normalize(path)
        return try {
            operation(normalized) { sftp ->
                toEntry(sftp, Paths.parent(normalized) ?: "/", Paths.name(normalized), sftp.stat(escape(normalized)), isStatResult = true)
            }
        } catch (e: RemoteNotFoundException) {
            null
        }
    }

    private fun toEntry(sftp: ChannelSftp, parent: String, name: String, attrs: SftpATTRS, isStatResult: Boolean = false): RemoteEntry {
        val path = if (name.isEmpty()) parent else Paths.join(parent, name)
        var isDirectory = attrs.isDir
        var size = attrs.size
        var modified = attrs.mTime
        val isLink = attrs.isLink && !isStatResult
        if (isLink) {
            // follow the link to learn whether it points to a folder; broken links stay plain files
            try {
                val target = sftp.stat(escape(path))
                isDirectory = target.isDir
                size = target.size
                modified = target.mTime
            } catch (ignored: SftpException) {
            }
        }

        return RemoteEntry(
            name = name,
            path = path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else size,
            modified = (modified.toLong() and 0xFFFFFFFFL) * 1000L,
            isSymlink = isLink,
        )
    }

    // endregion

    // region streams

    override fun openRead(path: String): InputStream {
        val normalized = Paths.normalize(path)
        val stream = operation(normalized) { it.get(escape(normalized)) }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } catch (e: IOException) {
                    // closing an interrupted download is not an error worth reporting
                }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val normalized = Paths.normalize(path)
        val stream = operation(normalized) { it.put(escape(normalized), ChannelSftp.OVERWRITE) }
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }
        }
    }

    // endregion

    // region mutations

    override fun mkdir(path: String) {
        val normalized = Paths.normalize(path)
        if (stat(normalized) != null) throw RemoteAlreadyExistsException(normalized)
        operation(normalized) { it.mkdir(escape(normalized)) }
    }

    override fun deleteFile(path: String) {
        val normalized = Paths.normalize(path)
        operation(normalized) { it.rm(escape(normalized)) }
    }

    override fun deleteDirectory(path: String) {
        val normalized = Paths.normalize(path)
        operation(normalized) { it.rmdir(escape(normalized)) }
    }

    override fun rename(from: String, to: String) {
        val source = Paths.normalize(from)
        val destination = Paths.normalize(to)
        if (stat(destination) != null) throw RemoteAlreadyExistsException(destination)
        operation(source) { it.rename(escape(source), escape(destination)) }
    }

    override fun setModified(path: String, millis: Long) {
        val normalized = Paths.normalize(path)
        try {
            operation(normalized) { it.setMtime(escape(normalized), (millis / 1000L).toInt()) }
        } catch (ignored: IOException) {
            // best effort
        }
    }

    // endregion

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    // region helpers

    /** JSch treats `*`, `?`, `[`, `]` and `\` in the last path segment as wildcards unless escaped. */
    private fun escape(path: String): String {
        val builder = StringBuilder(path.length + 4)
        path.forEach {
            if (it == '\\' || it == '*' || it == '?' || it == '[' || it == ']') builder.append('\\')
            builder.append(it)
        }
        return builder.toString()
    }

    private fun <T> operation(path: String, block: (ChannelSftp) -> T): T {
        val sftp = channel ?: throw ConnectionFailedException("Not connected")
        try {
            return block(sftp)
        } catch (e: SftpException) {
            throw mapSftpException(e, path)
        } catch (e: JSchException) {
            throw ConnectionFailedException(e.message, e)
        }
    }

    private fun mapSftpException(e: SftpException, path: String): IOException = when (e.id) {
        ChannelSftp.SSH_FX_NO_SUCH_FILE -> RemoteNotFoundException(path, e)
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> RemoteAccessDeniedException(path, e)
        ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST -> ConnectionFailedException(e.message, e)
        ChannelSftp.SSH_FX_OP_UNSUPPORTED -> UnsupportedRemoteOperationException(e.message ?: "Unsupported operation")
        else -> RemoteException(e.message ?: "SFTP error ${e.id}", e)
    }

    private fun mapConnectException(e: Exception): IOException {
        if (e is RemoteException) return e
        if (e is JSchHostKeyException || hostKeys.rejected) {
            val presented = hostKeys.presented
            if (presented != null) {
                return UntrustedServerException(connection.host, presented.serialize(), pinned?.serialize())
            }
        }

        val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) ||
                message.contains("authentication failures", ignoreCase = true) ||
                message.contains("Auth cancel", ignoreCase = true) ||
                message.contains("USERAUTH fail", ignoreCase = true) ->
                AuthenticationFailedException(e.message, e)

            rootCause is UnknownHostException -> ConnectionFailedException("Unknown host: ${connection.host}", e)
            rootCause is SocketException || rootCause is java.net.SocketTimeoutException || message.contains("timeout", true) ->
                ConnectionFailedException(rootCause.message ?: message, e)

            else -> ConnectionFailedException(message.ifEmpty { e.javaClass.simpleName }, e)
        }
    }

    // endregion

    /** Verifies the host key against the pinned identity and remembers what the server presented. */
    private class PinnedHostKeyRepository(private val pinned: PinnedIdentity?) : HostKeyRepository {
        @Volatile
        var presented: PinnedIdentity? = null

        @Volatile
        var rejected: Boolean = false

        override fun check(host: String?, key: ByteArray): Int {
            val identity = PinnedIdentity(PinnedIdentity.sshKeyType(key), PinnedIdentity.fingerprintOf(key))
            presented = identity
            val result = when {
                pinned == null -> HostKeyRepository.NOT_INCLUDED
                pinned.fingerprint == identity.fingerprint -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
            rejected = result != HostKeyRepository.OK
            return result
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}

        override fun remove(host: String?, type: String?) {}

        override fun remove(host: String?, type: String?, key: ByteArray?) {}

        override fun getKnownHostsRepositoryID(): String = "network-drive-pin"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers password and keyboard-interactive prompts with the stored password; never accepts host keys. */
    private class CredentialsUserInfo(
        private val password: String,
        private val passphrase: String,
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = passphrase

        override fun getPassword(): String = password

        override fun promptPassword(message: String?) = true

        override fun promptPassphrase(message: String?) = true

        override fun promptYesNo(message: String?) = false

        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? = prompt?.map { password }?.toTypedArray()
    }
}

/** One-time global JSch configuration. */
internal object JschSetup {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            useBouncyCastleWhereJceLacksSupport()
            initialized = true
        }
    }

    /**
     * Older Android versions have no JCE implementation of Ed25519/X25519, which modern OpenSSH servers and
     * keys rely on. BouncyCastle is bundled anyway (SMB needs it), so fall back to the BC based classes.
     */
    private fun useBouncyCastleWhereJceLacksSupport() {
        val bouncyCastlePresent = runCatching { Class.forName("org.bouncycastle.math.ec.rfc8032.Ed25519") }.isSuccess
        if (!bouncyCastlePresent) return

        val jceHasEd25519 = runCatching {
            java.security.KeyFactory.getInstance("Ed25519")
            java.security.KeyPairGenerator.getInstance("Ed25519")
        }.isSuccess
        if (!jceHasEd25519) {
            forceBouncyCastleEd25519()
        }

        val jceHasXdh = runCatching { javax.crypto.KeyAgreement.getInstance("XDH") }.isSuccess
        if (!jceHasXdh) {
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        }
    }

    internal fun forceBouncyCastleEd25519() {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
    }
}
