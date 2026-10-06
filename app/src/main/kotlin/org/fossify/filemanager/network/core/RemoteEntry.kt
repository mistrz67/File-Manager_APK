package org.fossify.filemanager.network.core

/** One file or folder on a (remote or local) file system. [path] is always absolute and uses `/`. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Last modification time in epoch milliseconds, 0 when unknown. */
    val modified: Long = 0L,
    val isSymlink: Boolean = false,
)
