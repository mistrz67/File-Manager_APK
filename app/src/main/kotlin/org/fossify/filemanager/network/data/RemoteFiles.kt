package org.fossify.filemanager.network.data

import android.content.Context
import org.fossify.filemanager.network.core.CancellationToken
import org.fossify.filemanager.network.core.ConflictPolicy
import org.fossify.filemanager.network.core.ConnectionConfig
import org.fossify.filemanager.network.core.LocalEndpoint
import org.fossify.filemanager.network.core.OperationCancelledException
import org.fossify.filemanager.network.core.Paths
import org.fossify.filemanager.network.core.RemoteAlreadyExistsException
import org.fossify.filemanager.network.core.RemoteClient
import org.fossify.filemanager.network.core.RemoteClientFactory
import org.fossify.filemanager.network.core.RemoteEntry
import org.fossify.filemanager.network.core.RemoteException
import org.fossify.filemanager.network.core.RemoteNotFoundException
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.core.TransferJob
import org.fossify.filemanager.network.core.TransferListener
import org.fossify.filemanager.network.core.TransferMode
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Blocking operations on remote paths (`remote://<id>/...`) used by the UI. Always call from a background thread.
 */
class RemoteFiles(context: Context, private val manager: NetworkManager) {
    val cache = RemoteCache(context)

    class TestResult(val initialPath: String, val itemCount: Int)

    fun list(path: String): List<RemoteEntry> = withClient(path, retryOnStale = true) { client, inner -> client.list(inner) }

    fun stat(path: String): RemoteEntry? = withClient(path, retryOnStale = true) { client, inner -> client.stat(inner) }

    /** Folder the drive opens in; connects to find out. Returned as a remote path. */
    fun initialPath(connectionId: String): String {
        val config = manager.config(connectionId)
        return manager.pool.use(config, retryOnStale = true) { RemotePath.build(connectionId, it.initialDirectory()) }
    }

    fun createFolder(parentPath: String, name: String): String {
        val target = RemotePath.child(parentPath, name)
        withClient(target) { client, inner -> client.mkdir(inner) }
        return target
    }

    fun createFile(parentPath: String, name: String): String {
        val target = RemotePath.child(parentPath, name)
        withClient(target) { client, inner ->
            if (client.stat(inner) != null) throw RemoteAlreadyExistsException(inner)
            client.openWrite(inner).close()
        }
        return target
    }

    /** Renames an item inside its folder and returns the new remote path. */
    fun rename(path: String, newName: String): String {
        val parent = RemotePath.parent(path) ?: throw RemoteException("Cannot rename a drive root")
        val target = RemotePath.child(parent, newName)
        withClient(path) { client, inner -> client.rename(inner, RemotePath.innerPath(target)) }
        return target
    }

    fun writeBytes(path: String, data: ByteArray) {
        withClient(path) { client, inner -> client.openWrite(inner).use { it.write(data) } }
    }

    fun measure(paths: List<String>, token: CancellationToken = CancellationToken()): Pair<Long, Int> {
        val connectionId = RemotePath.connectionId(paths.first()) ?: throw IllegalArgumentException("Not a remote path")
        return manager.engine.measure(manager.remoteEndpoint(connectionId), paths.map { RemotePath.innerPath(it) }, token)
    }

    /**
     * Makes sure a local copy of a remote file exists in the cache and returns it. A copy that is still up to date
     * (same size and modification time) is reused.
     */
    fun download(path: String, token: CancellationToken = CancellationToken(), listener: TransferListener = object : TransferListener {}): File {
        val parsed = RemotePath.parse(path) ?: throw IllegalArgumentException("Not a remote path: $path")
        val target = cache.fileFor(path)
        val entry = stat(path) ?: throw RemoteNotFoundException(path)
        if (entry.isDirectory) throw RemoteException("Cannot open a folder: $path")
        if (isUpToDate(target, entry)) return target

        target.parentFile?.mkdirs()
        val job = TransferJob(
            mode = TransferMode.COPY,
            sources = listOf(parsed.innerPath),
            source = manager.remoteEndpoint(parsed.connectionId),
            destination = LocalEndpoint(),
            destinationDir = target.parentFile!!.absolutePath,
            conflictPolicy = ConflictPolicy.OVERWRITE,
        )
        val result = manager.engine.run(job, token, listener)
        if (result.cancelled) throw OperationCancelledException()
        result.failures.firstOrNull()?.let { throw (it.error as? IOException) ?: RemoteException(it.error.message, it.error) }
        return target
    }

    /** Connects with a configuration that may not be saved yet, to validate it. Never touches the pool. */
    fun test(config: ConnectionConfig): TestResult {
        val client = RemoteClientFactory.create(config)
        try {
            client.connect()
            val initial = client.initialDirectory()
            val count = try {
                client.list(initial).size
            } catch (e: RemoteNotFoundException) {
                // an initial folder that does not exist is a configuration error worth reporting
                throw e
            }
            return TestResult(initial, count)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun isUpToDate(file: File, entry: RemoteEntry): Boolean {
        return file.isFile && entry.modified > 0 && file.length() == entry.size && abs(file.lastModified() - entry.modified) < 2_000
    }

    private fun <T> withClient(path: String, retryOnStale: Boolean = false, block: (RemoteClient, String) -> T): T {
        val parsed = RemotePath.parse(path) ?: throw IllegalArgumentException("Not a remote path: $path")
        val config = manager.config(parsed.connectionId)
        return manager.pool.use(config, retryOnStale) { block(it, Paths.normalize(parsed.innerPath)) }
    }
}
