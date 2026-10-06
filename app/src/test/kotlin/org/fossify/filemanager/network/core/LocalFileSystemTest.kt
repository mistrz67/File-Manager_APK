package org.fossify.filemanager.network.core

import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalFileSystemTest : FileSystemContract() {
    @get:Rule
    val folder = TemporaryFolder()

    override val root: String get() = folder.root.absolutePath

    override val supportsUnicodeNames: Boolean get() = jvmHandlesUnicodeFileNames

    override fun openFileSystem(): FileSystem = LocalFileSystem()
}
