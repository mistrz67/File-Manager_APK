package org.fossify.filemanager.network.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsTest {
    @Test
    fun normalizeCollapsesAndResolves() {
        assertEquals("/", Paths.normalize(""))
        assertEquals("/", Paths.normalize("/"))
        assertEquals("/a/b", Paths.normalize("a//b/"))
        assertEquals("/a/c", Paths.normalize("/a/b/../c"))
        assertEquals("/", Paths.normalize("/../.."))
        assertEquals("/a/b", Paths.normalize("/a/./b"))
    }

    @Test
    fun backslashesAreOrdinaryCharacters() {
        assertEquals("/dir/back\\slash.txt", Paths.normalize("/dir/back\\slash.txt"))
    }

    @Test
    fun parentNameAndJoin() {
        assertNull(Paths.parent("/"))
        assertEquals("/", Paths.parent("/a"))
        assertEquals("/a", Paths.parent("/a/b"))
        assertEquals("b", Paths.name("/a/b"))
        assertEquals("", Paths.name("/"))
        assertEquals("/a/b", Paths.join("/a", "b"))
        assertEquals("/b", Paths.join("/", "b"))
        assertEquals("/a/b/c", Paths.join("/a", "b", "c"))
    }

    @Test
    fun sameOrChild() {
        assertTrue(Paths.isSameOrChild("/a/b", "/a"))
        assertTrue(Paths.isSameOrChild("/a", "/a"))
        assertFalse(Paths.isSameOrChild("/ab", "/a"))
        assertTrue(Paths.isSameOrChild("/anything", "/"))
    }

    @Test
    fun remotePathRoundTrip() {
        val root = RemotePath.build("abc123", "/")
        assertEquals("remote://abc123", root)
        assertTrue(RemotePath.isRemote(root))
        assertFalse(RemotePath.isRemote("/storage/emulated/0"))
        assertEquals(RemotePath.Parsed("abc123", "/"), RemotePath.parse(root))

        val file = RemotePath.child(RemotePath.child(root, "docs"), "my file.txt")
        assertEquals("remote://abc123/docs/my file.txt", file)
        assertEquals(RemotePath.Parsed("abc123", "/docs/my file.txt"), RemotePath.parse(file))
        assertEquals("my file.txt", RemotePath.name(file))
        assertEquals("remote://abc123/docs", RemotePath.parent(file))
        assertEquals(root, RemotePath.parent("remote://abc123/docs"))
        assertNull(RemotePath.parent(root))
        assertTrue(RemotePath.isRoot(root))
        assertEquals(listOf(root, "remote://abc123/docs", file), RemotePath.chain(file))
    }

    @Test
    fun remotePathWithTrailingSlashIsRoot() {
        assertEquals(RemotePath.Parsed("id", "/"), RemotePath.parse("remote://id/"))
        assertNull(RemotePath.parse("remote://"))
        assertNull(RemotePath.parse("/local/path"))
    }

    @Test
    fun pinnedIdentityParsing() {
        val identity = PinnedIdentity.parse("ssh-ed25519 SHA256:abc")
        assertEquals(PinnedIdentity("ssh-ed25519", "SHA256:abc"), identity)
        assertEquals("ssh-ed25519 SHA256:abc", identity!!.serialize())
        assertNull(PinnedIdentity.parse(""))
        assertNull(PinnedIdentity.parse("garbage"))
        assertNull(PinnedIdentity.parse(null))
    }

    @Test
    fun sshKeyTypeFromBlob() {
        val type = "ssh-ed25519".toByteArray()
        val blob = byteArrayOf(0, 0, 0, type.size.toByte()) + type + byteArrayOf(1, 2, 3)
        assertEquals("ssh-ed25519", PinnedIdentity.sshKeyType(blob))
        assertEquals("unknown", PinnedIdentity.sshKeyType(byteArrayOf(1)))
    }

    @Test
    fun displayAddress() {
        val smb = NetworkConnection("1", "nas", Protocol.SMB, "nas.local", share = "Public")
        assertEquals("smb://nas.local/Public", smb.displayAddress())
        val sftp = NetworkConnection("2", "srv", Protocol.SFTP, "10.0.0.2", port = 2222)
        assertEquals("sftp://10.0.0.2:2222", sftp.displayAddress())
        val v6 = NetworkConnection("3", "v6", Protocol.FTP, "fe80::1")
        assertEquals("ftp://[fe80::1]", v6.displayAddress())
        assertEquals(445, smb.effectivePort)
        assertEquals(2222, sftp.effectivePort)
    }
}
