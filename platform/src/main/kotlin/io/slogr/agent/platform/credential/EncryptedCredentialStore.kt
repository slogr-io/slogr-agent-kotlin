package io.slogr.agent.platform.credential

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.interfaces.CredentialStore
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec as HmacKeySpec

/**
 * Stores [AgentCredential] as AES-256-GCM encrypted JSON on disk.
 *
 * Key derivation: `HMAC-SHA256(MachineIdentity.fingerprint(), "slogr-credential-v1")`
 * — stable across restarts, protects against casual filesystem reads.
 *
 * File format: `[12-byte IV][ciphertext]` (raw bytes, no encoding).
 */
class EncryptedCredentialStore(dataDir: String) : CredentialStore {

    private val log     = LoggerFactory.getLogger(EncryptedCredentialStore::class.java)
    private val file    = File(dataDir, "credential.enc")
    private val json    = Json { ignoreUnknownKeys = true }
    private val secretKey: SecretKey = deriveKey()

    override fun load(): AgentCredential? {
        if (!file.exists()) return null
        return try {
            val bytes      = file.readBytes()
            val iv         = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val plain      = decrypt(iv, ciphertext)
            json.decodeFromString(AgentCredential.serializer(), plain)
        } catch (e: Exception) {
            log.warn("Failed to load credential: ${e.message}")
            null
        }
    }

    override fun store(credential: AgentCredential) {
        file.parentFile?.mkdirs()
        val plain      = json.encodeToString(AgentCredential.serializer(), credential)
        val iv         = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = encrypt(iv, plain)
        file.writeBytes(iv + ciphertext)
        // Best-effort: set file permissions to owner-only on Unix
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
        runCatching { file.setWritable(false, false); file.setWritable(true, true) }
    }

    override fun delete() {
        file.delete()
    }

    override fun isConnected(): Boolean = load() != null

    // ── Crypto ─────────────────────────────────────────────────────────────────

    private fun encrypt(iv: ByteArray, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        const val AES_GCM       = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES  = 12
        const val GCM_TAG_BITS  = 128

        fun deriveKey(): SecretKey {
            val fingerprint = MachineIdentity.fingerprint()
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(HmacKeySpec("slogr-credential-v1".toByteArray(), "HmacSHA256"))
            val raw = hmac.doFinal(fingerprint.toByteArray())
            return SecretKeySpec(raw, "AES")   // 256-bit (32 bytes from SHA-256)
        }
    }
}
