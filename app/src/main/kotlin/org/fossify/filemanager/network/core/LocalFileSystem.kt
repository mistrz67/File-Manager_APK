package org.fossify.filemanager.network.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/** [FileSystem] over `java.io.File` so the transfer engine can treat the phone storage like a remote drive. */
class LocalFileSystem : FileSystem {
    override fun list(path: String): List<RemoteEntry> {
        val files = File(path).listFiles() ?: throw RemoteNotFoundException(path)
        return files.map { toEntry(it) }
    }

    override fun stat(path: String): RemoteEntry? {
        val file = File(path)
        // exists() follows symlinks, so also accept broken links
        val exists = file.exists() || Files.isSymbolicLink(file.toPath())
        return if (exists) toEntry(file) else null
    }

    override fun openRead(path: String): InputStream {
        try {
            return FileInputStream(path)
        } catch (e: IOException) {
            throw mapException(path, e)
        }
    }

    override fun openWrite(path: String): OutputStream {
        try {
            return FileOutputStream(path, false)
        } catch (e: IOException) {
            throw mapException(path, e)
        }
    }

    override fun mkdir(path: String) {
        val file = File(path)
        if (file.exists()) throw RemoteAlreadyExistsException(path)
        if (!file.mkdir()) throw RemoteException("Could not create folder $path")
    }

    override fun deleteFile(path: String) {
        if (!File(path).delete()) throw RemoteException("Could not delete $path")
    }

    override fun deleteDirectory(path: String) {
        if (!File(path).delete()) throw RemoteException("Could not delete folder $path")
    }

    override fun rename(from: String, to: String) {
        if (File(to).exists()) throw RemoteAlreadyExistsException(to)
        if (!File(from).renameTo(File(to))) throw RemoteException("Could not rename $from")
    }

    override fun setModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }

    private fun toEntry(file: File): RemoteEntry {
        val isSymlink = Files.isSymbolicLink(file.toPath())
        val isDirectory = file.isDirectory
        return RemoteEntry(
            name = file.name,
            path = file.absolutePath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else file.length(),
            modified = file.lastModified(),
            isSymlink = isSymlink && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    }

    private fun mapException(path: String, e: IOException): IOException {
        return when {
            !File(path).exists() && File(path).parentFile?.exists() == false -> RemoteNotFoundException(path, e)
            e.message?.contains("Permission denied", ignoreCase = true) == true -> RemoteAccessDeniedException(path, e)
            else -> e
        }
    }
}
