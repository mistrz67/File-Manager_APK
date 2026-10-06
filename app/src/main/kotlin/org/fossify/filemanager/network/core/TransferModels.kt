package org.fossify.filemanager.network.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferMode { COPY, MOVE }

/** What to do when an item with the same name already exists in the destination folder. */
enum class ConflictPolicy {
    /** Replace files, merge folders. */
    OVERWRITE,
    SKIP,

    /** Store the new item under a free name such as `report (1).pdf`. */
    KEEP_BOTH,
}

/** A leased file system; [release] returns it to where it came from, [discard] drops a possibly broken one. */
interface FsLease : Closeable {
    val fs: FileSystem

    fun release()

    fun discard()

    override fun close() = release()
}

/** A place files can live: the phone or a network drive. Equal [id]s denote the same file system. */
interface Endpoint {
    val id: String

    fun acquire(): FsLease
}

class LocalEndpoint : Endpoint {
    override val id: String = "local"

    override fun acquire(): FsLease = object : FsLease {
        override val fs: FileSystem = LocalFileSystem()

        override fun release() {}

        override fun discard() {}
    }
}

class RemoteEndpoint(private val pool: ClientPool, private val config: ConnectionConfig) : Endpoint {
    override val id: String = config.connection.id

    override fun acquire(): FsLease {
        val lease = pool.acquire(config)
        return object : FsLease {
            override val fs: FileSystem = lease.client

            override fun release() = lease.release()

            override fun discard() = lease.discard()
        }
    }
}

class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() = cancelled.set(true)

    fun throwIfCancelled() {
        if (cancelled.get()) throw OperationCancelledException()
    }
}

data class TransferJob(
    val mode: TransferMode,
    /** Absolute paths of the items to transfer, in the source endpoint. */
    val sources: List<String>,
    val source: Endpoint,
    val destination: Endpoint,
    /** Absolute path of the destination folder, in the destination endpoint. */
    val destinationDir: String,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val preserveModified: Boolean = true,
)

data class TransferProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val currentName: String,
)

interface TransferListener {
    /** Called while the items to transfer are being counted. */
    fun onScanning(filesFound: Int) {}

    /** Called regularly (throttled) with the overall progress. */
    fun onProgress(progress: TransferProgress) {}
}

class TransferFailure(val path: String, val error: Throwable)

data class TransferResult(
    /** Items (files) successfully transferred or deleted. */
    val completed: Int,
    val skipped: Int,
    val failures: List<TransferFailure>,
    val cancelled: Boolean,
    val bytes: Long,
) {
    val isSuccess: Boolean get() = failures.isEmpty() && !cancelled
}
