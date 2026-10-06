package org.fossify.filemanager.network.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class ConnectionJsonAndSecretBoxTest {
    private val sample = NetworkConnection(
        id = "abc123",
        name = "NAS",
        protocol = Protocol.SMB,
        host = "192.168.1.10",
        port = 4450,
        username = "jan",
        authMethod = AuthMethod.PASSWORD,
        domain = "WORKGROUP",
        share = "Dane",
        initialPath = "/Zdjęcia",
        ftpPassive = false,
        encoding = "windows-1250",
        trustedIdentity = "ssh-ed25519 SHA256:xyz",
        createdAt = 1234L,
    )

    @Test
    fun connectionsRoundTrip() {
        val other = NetworkConnection("id2", "Server", Protocol.SFTP, "host", authMethod = AuthMethod.PRIVATE_KEY)
        val restored = ConnectionJson.listFromJson(ConnectionJson.listToJson(listOf(sample, other)))
        assertEquals(listOf(sample, other), restored)
    }

    @Test
    fun brokenEntriesAreSkippedAndGarbageIsTolerated() {
        val text = """[{"id":"a","protocol":"smb","host":"h"},{"id":"","protocol":"smb","host":"h"},{"id":"b","protocol":"nope","host":"h"},"x",{"id":"c","protocol":"ftp","host":"h","future":42}]"""
        val restored = ConnectionJson.listFromJson(text)
        assertEquals(listOf("a", "c"), restored.map { it.id })
        assertEquals("h", restored[0].name) // the name falls back to the host

        assertTrue(ConnectionJson.listFromJson("not json").isEmpty())
        assertTrue(ConnectionJson.listFromJson(null).isEmpty())
        assertTrue(ConnectionJson.listFromJson("").isEmpty())
    }

    @Test
    fun secretsRoundTripAndStayRedactedInLogs() {
        val secrets = ConnectionSecrets("pass \"quoted\"", "-----BEGIN KEY-----\nline\n-----END KEY-----", "phrase")
        assertEquals(secrets, ConnectionJson.secretsFromJson(ConnectionJson.secretsToJson(secrets)))
        assertNull(ConnectionJson.secretsFromJson("{broken"))
        assertFalse(secrets.toString().contains("pass"))
        assertFalse(ConnectionConfig(sample, secrets).toString().contains("pass"))
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun sealedSecretsCanBeOpened() {
        val key = newKey()
        val blob = SecretBox.seal(key, "hunter2".toByteArray(), "conn1".toByteArray())
        assertTrue(blob.startsWith("v1:"))
        assertFalse(blob.contains("hunter2"))
        assertArrayEquals("hunter2".toByteArray(), SecretBox.open(key, blob, "conn1".toByteArray()))
    }

    @Test
    fun sealingTwiceGivesDifferentCiphertexts() {
        val key = newKey()
        val a = SecretBox.seal(key, "same".toByteArray(), ByteArray(0))
        val b = SecretBox.seal(key, "same".toByteArray(), ByteArray(0))
        assertNotEquals(a, b)
    }

    @Test
    fun wrongKeyWrongContextAndTamperingAreRejected() {
        val key = newKey()
        val blob = SecretBox.seal(key, "secret".toByteArray(), "conn1".toByteArray())

        assertNull(SecretBox.open(newKey(), blob, "conn1".toByteArray()))
        assertNull("a blob must not be usable for another connection", SecretBox.open(key, blob, "conn2".toByteArray()))

        val parts = blob.split(':').toMutableList()
        val tampered = java.util.Base64.getDecoder().decode(parts[2]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        parts[2] = java.util.Base64.getEncoder().encodeToString(tampered)
        assertNull(SecretBox.open(key, parts.joinToString(":"), "conn1".toByteArray()))

        assertNull(SecretBox.open(key, "garbage", ByteArray(0)))
        assertNull(SecretBox.open(key, "v2:AAAA:BBBB", ByteArray(0)))
        assertNull(SecretBox.open(key, "v1:!!!:???", ByteArray(0)))
        assertNotNull(SecretBox.open(key, blob, "conn1".toByteArray()))
    }
}
