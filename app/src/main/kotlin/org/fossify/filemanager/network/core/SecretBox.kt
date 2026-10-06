package org.fossify.filemanager.network.core

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM sealing of small secrets. The ciphertext is bound to [associatedData] (the id of the connection),
 * so a blob cannot be moved from one connection to another. Format: `v1:<base64 iv>:<base64 ciphertext+tag>`.
 *
 * The key is supplied by the caller; on Android it lives in the hardware backed Keystore and never leaves it.
 */
object SecretBox {
    private const val VERSION = "v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray, associatedData: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // the Keystore insists on generating the IV itself, so do not provide one
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plain)
        val encoder = Base64.getEncoder()
        return "$VERSION:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(encrypted)}"
    }

    /** Returns null when the blob is malformed, was sealed with another key, or was tampered with. */
    fun open(key: SecretKey, blob: String, associatedData: ByteArray): ByteArray? {
        val parts = blob.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[1])
            val encrypted = decoder.decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
