package org.fossify.filemanager.network.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal file system abstraction used by the transfer engine. All paths are absolute and use `/`.
 * Implementations are blocking and not thread safe; callers must use one instance from one thread at a time.
 */
interface FileSystem {
    /** Lists the direct children of a directory (never contains `.` or `..`). */
    fun list(path: String): List<RemoteEntry>

    /** Returns null when the path does not exist. */
    fun stat(path: String): RemoteEntry?

    fun openRead(path: String): InputStream

    /** Creates the file or truncates an existing one. The parent directory must exist. */
    fun openWrite(path: String): OutputStream

    /** Creates a single directory; the parent must exist. */
    fun mkdir(path: String)

    fun deleteFile(path: String)

    /** Deletes an empty directory. */
    fun deleteDirectory(path: String)

    /** Renames/moves within this file system. Throws [RemoteAlreadyExistsException] if [to] exists. */
    fun rename(from: String, to: String)

    /** Best effort; implementations may silently ignore failures. */
    fun setModified(path: String, millis: Long)
}

/** A connected client for one saved network drive. */
interface RemoteClient : FileSystem, Closeable {
    val connection: NetworkConnection

    val isConnected: Boolean

    /** Establishes the connection; does nothing when already connected. */
    fun connect()

    /** Folder the drive should open in, resolved from [NetworkConnection.initialPath] and the server defaults. */
    fun initialDirectory(): String

    /** Cheap liveness check used by the pool before handing out an idle client. */
    fun isHealthy(): Boolean = isConnected
}

fun FileSystem.exists(path: String): Boolean = stat(path) != null

/** Recursively deletes a file or directory tree. */
fun FileSystem.deleteRecursively(path: String, cancelled: () -> Boolean = { false }, onDeleted: (RemoteEntry) -> Unit = {}) {
    val entry = stat(path) ?: return
    if (entry.isDirectory && !entry.isSymlink) {
        for (child in list(path)) {
            if (cancelled()) throw OperationCancelledException()
            deleteRecursively(child.path, cancelled, onDeleted)
        }
        deleteDirectory(path)
    } else {
        deleteFile(path)
    }
    onDeleted(entry)
}
