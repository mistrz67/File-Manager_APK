package org.fossify.filemanager.network.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * Copies, moves and deletes file trees between the phone and network drives (in any direction). Runs on the
 * calling thread, reports progress through a [TransferListener] and stops promptly when its [CancellationToken]
 * is cancelled. Conflicts are resolved per top-level item according to [ConflictPolicy].
 */
class TransferEngine(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val progressIntervalMs: Long = 150L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Node(
        val sourcePath: String,
        val targetPath: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
        val topLevel: Int,
    )

    private class TopLevel(val sourcePath: String, val isDirectory: Boolean) {
        var failed = false
    }

    /** Holds the leases of a job and replaces them after a connection error. */
    private class Leases(private val source: Endpoint, private val destination: Endpoint) : Closeable {
        private var sourceLease: FsLease? = null
        private var destinationLease: FsLease? = null

        val sourceFs: FileSystem get() = (sourceLease ?: source.acquire().also { sourceLease = it }).fs
        val destinationFs: FileSystem get() = (destinationLease ?: destination.acquire().also { destinationLease = it }).fs

        fun discardAll() {
            sourceLease?.discard()
            destinationLease?.discard()
            sourceLease = null
            destinationLease = null
        }

        override fun close() {
            sourceLease?.release()
            destinationLease?.release()
            sourceLease = null
            destinationLease = null
        }
    }

    fun run(job: TransferJob, token: CancellationToken = CancellationToken(), listener: TransferListener = object : TransferListener {}): TransferResult {
        val failures = ArrayList<TransferFailure>()
        var completed = 0
        var skipped = 0
        var bytes = 0L

        Leases(job.source, job.destination).use { leases ->
            try {
                val tops = ArrayList<TopLevel>()
                val nodes = ArrayList<Node>()
                val sameEndpoint = job.source.id == job.destination.id
                val scannedFiles = IntArray(1)

                // 1. scan the sources and resolve conflicts at the top level
                val destinationNames = existingNames(leases.destinationFs, job.destinationDir)
                job.sources.forEach { sourcePath ->
                    token.throwIfCancelled()
                    try {
                        val entry = leases.sourceFs.stat(sourcePath) ?: throw RemoteNotFoundException(sourcePath)
                        if (sameEndpoint && entry.isDirectory && Paths.isSameOrChild(job.destinationDir, sourcePath)) {
                            throw RemoteException("Cannot copy a folder into itself: $sourcePath")
                        }
                        if (job.mode == TransferMode.MOVE && sameEndpoint && Paths.parent(sourcePath) == Paths.normalize(job.destinationDir)) {
                            skipped++ // already there
                            return@forEach
                        }

                        val targetName = resolveName(entry, destinationNames, job.conflictPolicy)
                        if (targetName == null) {
                            skipped++
                            return@forEach
                        }

                        destinationNames.add(targetName)
                        val index = tops.size
                        tops.add(TopLevel(sourcePath, entry.isDirectory))
                        scan(leases.sourceFs, entry, Paths.join(job.destinationDir, targetName), index, nodes, token, listener, scannedFiles, depth = 0)
                    } catch (e: OperationCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        failures.add(TransferFailure(sourcePath, e))
                    }
                }

                // 2. transfer
                val totalBytes = nodes.sumOf { if (it.isDirectory) 0L else it.size }
                val totalFiles = nodes.count { !it.isDirectory }
                val progress = ProgressTracker(totalBytes, totalFiles, listener)
                progress.report("", force = true)

                val createdDirectories = HashSet<String>()
                val renamedTops = HashSet<Int>()
                for (node in nodes) {
                    token.throwIfCancelled()
                    val top = tops[node.topLevel]
                    if (top.failed || node.topLevel in renamedTops) continue

                    try {
                        val isTopLevelNode = node.sourcePath == top.sourcePath
                        if (job.mode == TransferMode.MOVE && sameEndpoint && isTopLevelNode && tryRename(leases, node)) {
                            renamedTops.add(node.topLevel)
                            val files = nodes.count { it.topLevel == node.topLevel && !it.isDirectory }
                            completed += files
                            progress.skipBytes(nodes.filter { it.topLevel == node.topLevel }.sumOf { if (it.isDirectory) 0L else it.size }, files)
                            continue
                        }

                        if (node.isDirectory) {
                            ensureDirectory(leases, node.targetPath, createdDirectories)
                        } else {
                            progress.currentFile(Paths.name(node.sourcePath))
                            copyFile(leases, node, job, token, progress)
                            completed++
                            bytes += node.size
                            progress.fileDone()
                        }
                    } catch (e: OperationCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        top.failed = true
                        failures.add(TransferFailure(node.sourcePath, e))
                    }
                }

                // 3. a move deletes what was transferred completely
                if (job.mode == TransferMode.MOVE) {
                    tops.forEachIndexed { index, top ->
                        if (top.failed || index in renamedTops) return@forEachIndexed
                        token.throwIfCancelled()
                        try {
                            leases.sourceFs.deleteRecursively(top.sourcePath, cancelled = { token.isCancelled })
                        } catch (e: OperationCancelledException) {
                            throw e
                        } catch (e: Exception) {
                            failures.add(TransferFailure(top.sourcePath, e))
                        }
                    }
                }

                progress.report("", force = true)
            } catch (e: OperationCancelledException) {
                return TransferResult(completed, skipped, failures, cancelled = true, bytes = bytes)
            }
        }

        return TransferResult(completed, skipped, failures, cancelled = false, bytes = bytes)
    }

    /** Deletes files and folders, counting deleted files for the progress report. */
    fun delete(
        endpoint: Endpoint,
        paths: List<String>,
        token: CancellationToken = CancellationToken(),
        listener: TransferListener = object : TransferListener {},
    ): TransferResult {
        val failures = ArrayList<TransferFailure>()
        var completed = 0
        endpoint.acquire().use { lease ->
            try {
                paths.forEach { path ->
                    token.throwIfCancelled()
                    try {
                        lease.fs.deleteRecursively(path, cancelled = { token.isCancelled }) {
                            if (!it.isDirectory) {
                                completed++
                                listener.onProgress(TransferProgress(0, 0, completed, 0, it.name))
                            }
                        }
                    } catch (e: OperationCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        failures.add(TransferFailure(path, e))
                    }
                }
            } catch (e: OperationCancelledException) {
                return TransferResult(completed, 0, failures, cancelled = true, bytes = 0)
            }
        }
        return TransferResult(completed, 0, failures, cancelled = false, bytes = 0)
    }

    /** Total size and file count of a tree, used by property dialogs. */
    fun measure(endpoint: Endpoint, paths: List<String>, token: CancellationToken = CancellationToken()): Pair<Long, Int> {
        var size = 0L
        var files = 0
        endpoint.acquire().use { lease ->
            val queue = ArrayDeque<String>()
            queue.addAll(paths)
            while (queue.isNotEmpty()) {
                token.throwIfCancelled()
                val path = queue.removeFirst()
                val entry = lease.fs.stat(path) ?: continue
                if (entry.isDirectory && !entry.isSymlink) {
                    lease.fs.list(path).forEach { queue.add(it.path) }
                } else {
                    size += entry.size
                    files++
                }
            }
        }
        return size to files
    }

    // region planning

    private fun existingNames(fs: FileSystem, directory: String): MutableSet<String> {
        return try {
            fs.list(directory).mapTo(HashSet()) { it.name }
        } catch (e: RemoteNotFoundException) {
            fs.mkdirs(directory)
            HashSet()
        }
    }

    private fun FileSystem.mkdirs(path: String) {
        val missing = ArrayDeque<String>()
        var current: String? = Paths.normalize(path)
        while (current != null && current != "/" && stat(current) == null) {
            missing.addFirst(current)
            current = Paths.parent(current)
        }
        missing.forEach { mkdir(it) }
    }

    /** The name an item gets in the destination folder, or null when it is to be skipped. */
    private fun resolveName(entry: RemoteEntry, existing: Set<String>, policy: ConflictPolicy): String? {
        if (entry.name !in existing) return entry.name
        return when (policy) {
            ConflictPolicy.OVERWRITE -> entry.name
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> uniqueName(entry.name, entry.isDirectory, existing)
        }
    }

    private fun scan(
        fs: FileSystem,
        entry: RemoteEntry,
        targetPath: String,
        topLevel: Int,
        nodes: MutableList<Node>,
        token: CancellationToken,
        listener: TransferListener,
        scannedFiles: IntArray,
        depth: Int,
    ) {
        // symlinked folders are only followed when the user picked them; nested ones could form loops
        if (entry.isDirectory && entry.isSymlink && depth > 0) return

        nodes.add(Node(entry.path, targetPath, entry.isDirectory, entry.size, entry.modified, topLevel))
        if (!entry.isDirectory) {
            scannedFiles[0]++
            listener.onScanning(scannedFiles[0])
            return
        }

        for (child in fs.list(entry.path)) {
            token.throwIfCancelled()
            scan(fs, child, Paths.join(targetPath, child.name), topLevel, nodes, token, listener, scannedFiles, depth + 1)
        }
    }


    // endregion

    // region execution

    private fun tryRename(leases: Leases, node: Node): Boolean {
        return try {
            val destinationFs = leases.destinationFs
            if (destinationFs.stat(node.targetPath) != null) {
                // overwriting: let the generic path replace the files
                false
            } else {
                destinationFs.rename(node.sourcePath, node.targetPath)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun ensureDirectory(leases: Leases, path: String, created: MutableSet<String>) {
        if (path in created) return
        val fs = leases.destinationFs
        val existing = fs.stat(path)
        if (existing == null) {
            try {
                fs.mkdir(path)
            } catch (e: RemoteAlreadyExistsException) {
                // created concurrently, fine
            }
        } else if (!existing.isDirectory) {
            throw RemoteAlreadyExistsException(path)
        }
        created.add(path)
    }

    private fun copyFile(leases: Leases, node: Node, job: TransferJob, token: CancellationToken, progress: ProgressTracker) {
        var attempt = 0
        while (true) {
            val before = progress.bytesDone
            try {
                copyOnce(leases, node, token, progress)
                if (job.preserveModified && node.modified > 0) {
                    leases.destinationFs.setModified(node.targetPath, node.modified)
                }
                return
            } catch (e: ConnectionFailedException) {
                // the connection dropped mid-file: start over on fresh connections, once
                leases.discardAll()
                progress.rewind(before)
                if (attempt++ >= MAX_FILE_RETRIES) throw e
            }
        }
    }

    private fun copyOnce(leases: Leases, node: Node, token: CancellationToken, progress: ProgressTracker) {
        val sourceFs = leases.sourceFs
        val destinationFs = leases.destinationFs
        var completedOk = false
        try {
            sourceFs.openRead(node.sourcePath).use { input ->
                destinationFs.openWrite(node.targetPath).use { output ->
                    copyStream(input, output, token, progress)
                }
            }
            completedOk = true
        } finally {
            if (!completedOk) {
                // do not leave a truncated file behind
                runCatching { leases.destinationFs.deleteFile(node.targetPath) }
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, token: CancellationToken, progress: ProgressTracker) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            token.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            progress.bytes(read.toLong())
        }
        output.flush()
    }

    // endregion

    private inner class ProgressTracker(
        private val bytesTotal: Long,
        private val filesTotal: Int,
        private val listener: TransferListener,
    ) {
        var bytesDone = 0L
            private set
        private var filesDone = 0
        private var current = ""
        private var lastReport = 0L

        fun currentFile(name: String) {
            current = name
            report(name, force = true)
        }

        fun bytes(count: Long) {
            bytesDone += count
            report(current, force = false)
        }

        fun fileDone() {
            filesDone++
            report(current, force = false)
        }

        fun skipBytes(count: Long, files: Int) {
            bytesDone += count
            filesDone += files
            report(current, force = true)
        }

        fun rewind(to: Long) {
            bytesDone = to
        }

        fun report(name: String, force: Boolean) {
            val now = clock()
            if (!force && now - lastReport < progressIntervalMs) return
            lastReport = now
            listener.onProgress(TransferProgress(bytesDone, bytesTotal, filesDone, filesTotal, name))
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
        private const val MAX_FILE_RETRIES = 1

        /** `name (1).ext` style names that do not collide with [existing]. */
        fun uniqueName(name: String, isDirectory: Boolean, existing: Set<String>): String {
            val dot = name.lastIndexOf('.')
            val hasExtension = !isDirectory && dot > 0
            val base = if (hasExtension) name.substring(0, dot) else name
            val extension = if (hasExtension) name.substring(dot) else ""
            var counter = 1
            while (true) {
                val candidate = "$base ($counter)$extension"
                if (candidate !in existing) return candidate
                counter++
            }
        }
    }
}
