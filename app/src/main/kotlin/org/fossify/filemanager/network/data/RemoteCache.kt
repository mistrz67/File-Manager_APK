package org.fossify.filemanager.network.data

import android.content.Context
import org.fossify.filemanager.network.core.Paths
import org.fossify.filemanager.network.core.RemotePath
import java.io.File
import java.security.MessageDigest

/**
 * Local copies of remote files, made when a file is opened, shared or edited. They live in the app's cache
 * directory, so the system may also clear them, and old ones are purged on start.
 */
class RemoteCache(context: Context) {
    private val root = File(context.cacheDir, "remote")

    /** Folder that holds the cached copy of [remotePath]; the file keeps its real name inside it. */
    fun folderFor(remotePath: String): File {
        val parsed = RemotePath.parse(remotePath) ?: throw IllegalArgumentException("Not a remote path: $remotePath")
        return File(File(root, parsed.connectionId), digest(parsed.innerPath))
    }

    fun fileFor(remotePath: String): File = File(folderFor(remotePath), RemotePath.name(remotePath))

    /** Folder for temporary uploads (edited text) so the file can carry the real name. */
    fun uploadFolder(connectionId: String, name: String): File = File(File(root, "upload-$connectionId"), digest("$name${System.nanoTime()}"))

    fun clear(connectionId: String) {
        File(root, connectionId).deleteRecursively()
        File(root, "upload-$connectionId").deleteRecursively()
    }

    fun purgeOlderThan(maxAgeMs: Long) {
        val limit = System.currentTimeMillis() - maxAgeMs
        root.listFiles()?.forEach { drive ->
            drive.listFiles()?.forEach { entry ->
                if (entry.lastModified() < limit) entry.deleteRecursively()
            }
            if (drive.list().isNullOrEmpty()) drive.delete()
        }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(Paths.normalize(value).toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
