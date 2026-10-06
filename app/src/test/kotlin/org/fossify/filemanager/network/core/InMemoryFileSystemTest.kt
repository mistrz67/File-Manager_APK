package org.fossify.filemanager.network.core

/** Keeps the fake honest: it has to satisfy the same contract as the real implementations. */
class InMemoryFileSystemTest : FileSystemContract() {
    override val root = "/"
    override fun openFileSystem(): FileSystem = InMemoryClient()
}
