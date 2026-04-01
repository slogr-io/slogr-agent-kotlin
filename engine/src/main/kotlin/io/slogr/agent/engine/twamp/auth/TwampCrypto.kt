package io.slogr.agent.engine.twamp.auth

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * TWAMP cryptographic operations: AES-ECB, AES-CBC, HMAC-SHA1, PBKDF2.
 *
 * All keys are 128-bit (16 bytes). AES blocks are 16 bytes; callers must
 * supply data whose length is a multiple of 16 (no padding added here).
 */
object TwampCrypto {

    /**
     * Encrypt [data] with AES-ECB using [key] (no padding).
     * Used to encrypt the first 16-byte block of an authenticated-mode test packet.
     */
    fun encryptAesEcb(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * Decrypt [data] with AES-ECB using [key] (no padding).
     */
    fun decryptAesEcb(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * Encrypt [data] with AES-CBC using [key] and [iv] (no padding).
     * If [updateIv] is true, [iv] is mutated in-place with the last 16 bytes of ciphertext,
     * so the caller's IV advances for the next block (stateful streaming use).
     */
    fun encryptAesCbc(key: ByteArray, data: ByteArray, iv: ByteArray, updateIv: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(data)
        if (updateIv && ciphertext.size >= 16) {
            System.arraycopy(ciphertext, ciphertext.size - 16, iv, 0, 16)
        }
        return ciphertext
    }

    /**
     * Decrypt [data] with AES-CBC using [key] and [iv] (no padding).
     * If [updateIv] is true, [iv] is mutated in-place with the last 16 bytes of [data]
     * (the final ciphertext block becomes the next IV).
     */
    fun decryptAesCbc(key: ByteArray, data: ByteArray, iv: ByteArray, updateIv: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plaintext = cipher.doFinal(data)
        if (updateIv && data.size >= 16) {
            System.arraycopy(data, data.size - 16, iv, 0, 16)
        }
        return plaintext
    }

    /**
     * Compute HMAC-SHA1 of [data] using [key], truncated to 16 bytes.
     * Used as the HMAC field in authenticated/encrypted test packets.
     */
    fun hmacSha1Truncated(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data).copyOf(16)
    }

    /**
     * Derive a 128-bit (16-byte) key from [secret] using PBKDF2-HMAC-SHA1.
     *
     * @param secret   Passphrase bytes (UTF-8 encoded shared secret).
     * @param salt     16-byte random salt from ServerGreeting.
     * @param count    Iteration count from ServerGreeting.
     */
    fun pbkdf2(secret: ByteArray, salt: ByteArray, count: Int): ByteArray {
        val chars = secret.map { it.toInt().toChar() }.toCharArray()
        val spec = PBEKeySpec(chars, salt, count, 128)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(spec)
            .encoded
    }
}
