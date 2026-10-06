package org.fossify.filemanager.network.core

import java.security.MessageDigest
import java.util.Base64

/**
 * The identity of a server the user explicitly trusted: the type of key/certificate and the SHA-256
 * fingerprint of it. Serialized as `<type> SHA256:<base64>` (the format OpenSSH prints).
 */
data class PinnedIdentity(val type: String, val fingerprint: String) {
    fun serialize() = "$type $fingerprint"

    companion object {
        const val TYPE_TLS = "tls"

        fun parse(value: String?): PinnedIdentity? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().split(' ', limit = 2)
            return if (parts.size == 2 && parts[1].startsWith("SHA256:")) PinnedIdentity(parts[0], parts[1]) else null
        }

        fun fingerprintOf(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        /** Colon separated upper-case hex, the way certificate fingerprints are usually displayed. */
        fun hexFingerprintOf(data: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString(":") { "%02X".format(it) }
        }

        /** Type name stored at the beginning of an SSH public key blob, e.g. `ssh-ed25519`. */
        fun sshKeyType(blob: ByteArray): String {
            if (blob.size < 4) return "unknown"
            val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
                ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
            if (len < 0 || 4 + len > blob.size) return "unknown"
            return String(blob, 4, len, Charsets.US_ASCII)
        }
    }
}
