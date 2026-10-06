package org.fossify.filemanager.network.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap

/** Simple in-memory [RemoteClient] used to test the pool and the transfer engine without a server. */
class InMemoryClient(
    override val connection: NetworkConnection = NetworkConnection("mem", "memory", Protocol.SFTP, "localhost"),
) : RemoteClient {
    private class Node(val isDirectory: Boolean, var data: ByteArray = ByteArray(0), var modified: Long = 0L)

    private val nodes = TreeMap<String, Node>().apply { put("/", Node(true)) }

    var connected = false
    var connectCount = 0
    var closeCount = 0
    var healthy = true

    /** Number of upcoming [openRead]/[openWrite] calls that fail with a connection error after some bytes. */
    var failTransfers = 0

    /** Called for every operation, lets tests simulate dropped connections. */
    var beforeOperation: (String) -> Unit = {}

    override val isConnected: Boolean get() = connected

    override fun connect() {
        connected = true
        connectCount++
    }

    override fun initialDirectory() = "/"

    override fun isHealthy() = connected && healthy

    override fun close() {
        connected = false
        closeCount++
    }

    @Synchronized
    override fun list(path: String): List<RemoteEntry> {
        beforeOperation("list")
        val dir = Paths.normalize(path)
        val node = nodes[dir] ?: throw RemoteNotFoundException(dir)
        if (!node.isDirectory) throw RemoteNotFoundException(dir)
        return nodes.entries
            .filter { it.key != dir && Paths.parent(it.key) == dir }
            .map { toEntry(it.key, it.value) }
    }

    @Synchronized
    override fun stat(path: String): RemoteEntry? {
        beforeOperation("stat")
        val p = Paths.normalize(path)
        return nodes[p]?.let { toEntry(p, it) }
    }

    @Synchronized
    override fun openRead(path: String): InputStream {
        beforeOperation("read")
        val p = Paths.normalize(path)
        val node = nodes[p]?.takeIf { !it.isDirectory } ?: throw RemoteNotFoundException(p)
        if (failTransfers > 0) {
            failTransfers--
            return object : InputStream() {
                private var served = 0
                override fun read(): Int = throw ConnectionFailedException("dropped")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 0) throw ConnectionFailedException("dropped")
                    served = minOf(len, 16, node.data.size).also { System.arraycopy(node.data, 0, b, off, it) }
                    return served
                }
            }
        }
        return ByteArrayInputStream(node.data)
    }

    @Synchronized
    override fun openWrite(path: String): OutputStream {
        beforeOperation("write")
        val p = Paths.normalize(path)
        val parent = Paths.parent(p) ?: throw RemoteException("cannot write root")
        if (nodes[parent]?.isDirectory != true) throw RemoteNotFoundException(parent)
        if (nodes[p]?.isDirectory == true) throw RemoteAlreadyExistsException(p)
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                synchronized(this@InMemoryClient) { nodes[p] = Node(false, toByteArray(), System.currentTimeMillis()) }
            }
        }
    }

    @Synchronized
    override fun mkdir(path: String) {
        beforeOperation("mkdir")
        val p = Paths.normalize(path)
        if (nodes.containsKey(p)) throw RemoteAlreadyExistsException(p)
        val parent = Paths.parent(p) ?: throw RemoteAlreadyExistsException(p)
        if (nodes[parent]?.isDirectory != true) throw RemoteNotFoundException(parent)
        nodes[p] = Node(true)
    }

    @Synchronized
    override fun deleteFile(path: String) {
        beforeOperation("deleteFile")
        val p = Paths.normalize(path)
        val node = nodes[p] ?: throw RemoteNotFoundException(p)
        if (node.isDirectory) throw RemoteException("is a directory")
        nodes.remove(p)
    }

    @Synchronized
    override fun deleteDirectory(path: String) {
        beforeOperation("deleteDirectory")
        val p = Paths.normalize(path)
        val node = nodes[p] ?: throw RemoteNotFoundException(p)
        if (!node.isDirectory) throw RemoteException("not a directory")
        if (nodes.keys.any { it != p && Paths.parent(it) == p }) throw RemoteNotEmptyException(p)
        nodes.remove(p)
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        beforeOperation("rename")
        val source = Paths.normalize(from)
        val target = Paths.normalize(to)
        if (nodes.containsKey(target)) throw RemoteAlreadyExistsException(target)
        if (!nodes.containsKey(source)) throw RemoteNotFoundException(source)
        val moved = nodes.keys.filter { Paths.isSameOrChild(it, source) }.associateWith { nodes[it]!! }
        moved.keys.forEach { nodes.remove(it) }
        moved.forEach { (key, node) -> nodes[target + key.removePrefix(source)] = node }
    }

    @Synchronized
    override fun setModified(path: String, millis: Long) {
        nodes[Paths.normalize(path)]?.modified = millis
    }

    @Synchronized
    fun putFile(path: String, content: String, modified: Long = 1_000_000L) {
        val p = Paths.normalize(path)
        mkdirsInternal(Paths.parent(p)!!)
        nodes[p] = Node(false, content.toByteArray(), modified)
    }

    @Synchronized
    fun readText(path: String): String? = nodes[Paths.normalize(path)]?.takeIf { !it.isDirectory }?.let { String(it.data) }

    @Synchronized
    fun mkdirs(path: String) = mkdirsInternal(Paths.normalize(path))

    private fun mkdirsInternal(path: String) {
        var current = ""
        Paths.segments(path).forEach {
            current = "$current/$it"
            nodes.getOrPut(current) { Node(true) }
        }
    }

    @Synchronized
    fun exists(path: String) = nodes.containsKey(Paths.normalize(path))

    @Synchronized
    fun paths(): List<String> = nodes.keys.filter { it != "/" }

    private fun toEntry(path: String, node: Node) =
        RemoteEntry(Paths.name(path), path, node.isDirectory, if (node.isDirectory) 0 else node.data.size.toLong(), node.modified)
}

/** [Endpoint] that always hands out the same in-memory client (so tests can inspect it). */
class InMemoryEndpoint(override val id: String, val client: InMemoryClient) : Endpoint {
    var acquireCount = 0

    override fun acquire(): FsLease {
        acquireCount++
        return object : FsLease {
            override val fs: FileSystem = client
            override fun release() {}
            override fun discard() {}
        }
    }
}
