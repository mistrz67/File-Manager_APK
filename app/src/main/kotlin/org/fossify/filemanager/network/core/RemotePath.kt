package org.fossify.filemanager.network.core

/**
 * Paths of network drives as seen by the file manager UI: `remote://<connectionId>/<path inside the drive>`.
 * The root of a drive is `remote://<connectionId>` (no trailing slash), which keeps the UI code that trims
 * trailing slashes consistent.
 */
object RemotePath {
    private const val PREFIX = "remote://"

    data class Parsed(val connectionId: String, val innerPath: String)

    fun isRemote(path: String?): Boolean = path != null && path.startsWith(PREFIX)

    fun build(connectionId: String, innerPath: String): String {
        val inner = Paths.normalize(innerPath)
        return if (inner == "/") "$PREFIX$connectionId" else "$PREFIX$connectionId$inner"
    }

    fun parse(path: String): Parsed? {
        if (!isRemote(path)) return null
        val rest = path.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        val id = if (slash == -1) rest else rest.substring(0, slash)
        if (id.isEmpty()) return null
        val inner = if (slash == -1) "/" else Paths.normalize(rest.substring(slash))
        return Parsed(id, inner)
    }

    fun connectionId(path: String): String? = parse(path)?.connectionId

    fun innerPath(path: String): String = parse(path)?.innerPath ?: path

    fun isRoot(path: String): Boolean = parse(path)?.innerPath == "/"

    /** Parent of [path]; null when [path] is the root of a drive or not a remote path. */
    fun parent(path: String): String? {
        val parsed = parse(path) ?: return null
        val parentInner = Paths.parent(parsed.innerPath) ?: return null
        return build(parsed.connectionId, parentInner)
    }

    fun child(parentPath: String, name: String): String {
        val parsed = parse(parentPath) ?: return parentPath
        return build(parsed.connectionId, Paths.join(parsed.innerPath, name))
    }

    fun name(path: String): String = parse(path)?.let { Paths.name(it.innerPath) } ?: path.substringAfterLast('/')

    /** All ancestors including [path] itself, from the drive root down to the path. */
    fun chain(path: String): List<String> {
        val parsed = parse(path) ?: return emptyList()
        val result = ArrayList<String>()
        var current = "/"
        result.add(build(parsed.connectionId, current))
        Paths.segments(parsed.innerPath).forEach {
            current = Paths.join(current, it)
            result.add(build(parsed.connectionId, current))
        }
        return result
    }
}

/** Helpers for absolute `/`-separated paths, shared by local and remote file systems. */
object Paths {
    /** Collapses repeated separators and resolves `.` and `..`. Always returns an absolute path. */
    fun normalize(path: String): String {
        val stack = ArrayList<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(it)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun segments(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }

    fun name(path: String): String = segments(path).lastOrNull() ?: ""

    /** Parent of an absolute path, null for the root. */
    fun parent(path: String): String? {
        val segments = segments(path)
        if (segments.isEmpty()) return null
        return "/" + segments.dropLast(1).joinToString("/")
    }

    fun join(parent: String, name: String): String {
        return if (parent.endsWith("/")) normalize(parent + name) else normalize("$parent/$name")
    }

    fun join(parent: String, name: String, vararg more: String): String {
        var result = join(parent, name)
        more.forEach { result = join(result, it) }
        return result
    }

    fun isSameOrChild(candidate: String, ancestor: String): Boolean {
        val c = normalize(candidate)
        val a = normalize(ancestor)
        return a == "/" || c == a || c.startsWith("$a/")
    }
}
