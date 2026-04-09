package io.slogr.desktop.core.settings

import java.net.InetAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Encrypts and stores the API key on disk using AES-256-GCM.
 *
 * The encryption key is derived from machine-specific properties (hostname + username).
 * This is a cross-platform fallback; OS-specific stores (DPAPI, Keychain) can be
 * added as providers in a future phase.
 */
class EncryptedKeyStore(private val dataDir: Path) {

    private val keyFile = dataDir.resolve(".keystore")

    private companion object {
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }

    fun store(apiKey: String) {
        dataDir.createDirectories()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        keyFile.writeBytes(iv + encrypted)
    }

    fun load(): String? {
        if (!keyFile.exists()) return null
        return try {
            val data = keyFile.readBytes()
            if (data.size <= IV_LENGTH) return null
            val iv = data.sliceArray(0 until IV_LENGTH)
            val encrypted = data.sliceArray(IV_LENGTH until data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun delete() {
        keyFile.deleteIfExists()
    }

    private fun deriveKey(): SecretKeySpec {
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "localhost"
        }
        val username = System.getProperty("user.name") ?: "unknown"
        val material = "slogr-desktop:$hostname:$username"
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hash, "AES")
    }
}
