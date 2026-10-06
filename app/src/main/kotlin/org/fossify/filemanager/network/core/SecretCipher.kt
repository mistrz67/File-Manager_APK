package org.fossify.filemanager.network.core

import javax.crypto.SecretKey

/** Encrypts small secrets for storage. */
interface SecretCipher {
    fun encrypt(plain: ByteArray, associatedData: ByteArray): String

    /** Returns null when the blob cannot be decrypted (key lost, data corrupted or tampered with). */
    fun decrypt(blob: String, associatedData: ByteArray): ByteArray?
}

/** [SecretCipher] using AES-GCM with a key obtained from [keyProvider] (null when the key is unavailable). */
class KeySecretCipher(private val keyProvider: () -> SecretKey?) : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): String {
        val key = keyProvider() ?: throw IllegalStateException("No encryption key available")
        return SecretBox.seal(key, plain, associatedData)
    }

    override fun decrypt(blob: String, associatedData: ByteArray): ByteArray? {
        val key = try {
            keyProvider()
        } catch (e: Exception) {
            null
        } ?: return null
        return SecretBox.open(key, blob, associatedData)
    }
}
