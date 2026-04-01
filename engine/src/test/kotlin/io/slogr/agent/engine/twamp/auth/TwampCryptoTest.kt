package io.slogr.agent.engine.twamp.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Known-answer tests (KATs) for TwampCrypto.
 *
 * Vectors are computed from standard Java crypto or cross-validated with
 * RFC test vectors where available. The PBKDF2 vector matches RFC 6070 §2
 * (password="password", salt="salt", c=1, dkLen=16).
 */
class TwampCryptoTest {

    // ── PBKDF2 ────────────────────────────────────────────────────────────────

    /**
     * RFC 6070 §2 vector: password="password", salt="salt", c=1, dkLen=20.
     * We use dkLen=16 (128 bits). The first 16 bytes of the full 20-byte
     * derived key must match: 0x0c60c80f 961f0e71 f3a9b524 af601206.
     */
    @Test fun `pbkdf2 RFC 6070 vector password-salt-c1`() {
        val key = TwampCrypto.pbkdf2(
            secret = "password".toByteArray(Charsets.UTF_8),
            salt   = "salt".toByteArray(Charsets.UTF_8),
            count  = 1
        )
        assertEquals(16, key.size)
        val expected = byteArrayOf(
            0x0c.toByte(), 0x60.toByte(), 0xc8.toByte(), 0x0f.toByte(),
            0x96.toByte(), 0x1f.toByte(), 0x0e.toByte(), 0x71.toByte(),
            0xf3.toByte(), 0xa9.toByte(), 0xb5.toByte(), 0x24.toByte(),
            0xaf.toByte(), 0x60.toByte(), 0x12.toByte(), 0x06.toByte()
        )
        assertArrayEquals(expected, key)
    }

    @Test fun `pbkdf2 returns 16 bytes`() {
        val key = TwampCrypto.pbkdf2("pass".toByteArray(), "saltsalt".toByteArray(), 100)
        assertEquals(16, key.size)
    }

    // ── AES-ECB ───────────────────────────────────────────────────────────────

    /**
     * NIST AES-128 ECB known-answer (FIPS 197 Appendix B):
     *   key       = 2b7e1516 28aed2a6 abf71588 09cf4f3c
     *   plaintext = 3243f6a8 885a308d 313198a2 e0370734
     *   cipher    = 3925841d 02dc09fb dc118597 196a0b32
     */
    @Test fun `encryptAesEcb NIST FIPS-197 vector`() {
        val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
        val plain = hexToBytes("3243f6a8885a308d313198a2e0370734")
        val expected = hexToBytes("3925841d02dc09fbdc118597196a0b32")
        assertArrayEquals(expected, TwampCrypto.encryptAesEcb(key, plain))
    }

    @Test fun `decryptAesEcb reverses encryptAesEcb`() {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(16) { (it * 3).toByte() }
        val cipher = TwampCrypto.encryptAesEcb(key, plain)
        assertArrayEquals(plain, TwampCrypto.decryptAesEcb(key, cipher))
    }

    @Test fun `encryptAesEcb 32-byte input produces 32 bytes`() {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(32)
        assertEquals(32, TwampCrypto.encryptAesEcb(key, plain).size)
    }

    // ── AES-CBC ───────────────────────────────────────────────────────────────

    /**
     * NIST SP 800-38A, Section F.2.1 CBC-AES128.Encrypt:
     *   key   = 2b7e1516 28aed2a6 abf71588 09cf4f3c
     *   IV    = 00010203 04050607 08090a0b 0c0d0e0f
     *   plain = 6bc1bee2 2e409f96 e93d7e11 7393172a   (block 1)
     *   cipher= 7649abac 8119b246 cee98e9b 12e9197d   (block 1)
     */
    @Test fun `encryptAesCbc NIST SP800-38A block-1 vector`() {
        val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val plain = hexToBytes("6bc1bee22e409f96e93d7e117393172a")
        val expected = hexToBytes("7649abac8119b246cee98e9b12e9197d")
        assertArrayEquals(expected, TwampCrypto.encryptAesCbc(key, plain, iv, updateIv = false))
    }

    @Test fun `decryptAesCbc reverses encryptAesCbc`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 5).toByte() }
        val plain = ByteArray(32) { (it * 7).toByte() }
        val cipher = TwampCrypto.encryptAesCbc(key, plain, iv.copyOf(), updateIv = false)
        assertArrayEquals(plain, TwampCrypto.decryptAesCbc(key, cipher, iv.copyOf(), updateIv = false))
    }

    @Test fun `encryptAesCbc updateIv mutates iv to last ciphertext block`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16)
        val plain = ByteArray(32) { it.toByte() }
        val cipher = TwampCrypto.encryptAesCbc(key, plain, iv, updateIv = true)
        // After updateIv, iv should equal the last 16 bytes of ciphertext
        assertArrayEquals(cipher.copyOfRange(16, 32), iv)
    }

    @Test fun `decryptAesCbc updateIv mutates iv to last ciphertext block`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16)
        val plain = ByteArray(32) { it.toByte() }
        val cipher = TwampCrypto.encryptAesCbc(key, plain, iv.copyOf(), updateIv = false)
        val ivForDecrypt = iv.copyOf()
        TwampCrypto.decryptAesCbc(key, cipher, ivForDecrypt, updateIv = true)
        // After decrypt with updateIv, ivForDecrypt should equal last 16 bytes of ciphertext
        assertArrayEquals(cipher.copyOfRange(16, 32), ivForDecrypt)
    }

    // ── HMAC-SHA1 ─────────────────────────────────────────────────────────────

    /**
     * RFC 2202 §3 Test Case 1:
     *   key  = 0b0b0b0b 0b0b0b0b 0b0b0b0b 0b0b0b0b 0b0b0b0b  (20 bytes)
     *   data = "Hi There"
     *   HMAC-SHA1 = b617318655 057264e28b c0b6fb378c 8ef146be00  (20 bytes)
     * Truncated to 16: b61731865505726 4e28bc0b6fb378c8
     */
    @Test fun `hmacSha1Truncated RFC 2202 test-case-1`() {
        val key = ByteArray(20) { 0x0b.toByte() }
        val data = "Hi There".toByteArray(Charsets.US_ASCII)
        val result = TwampCrypto.hmacSha1Truncated(key, data)
        assertEquals(16, result.size)
        val expected = hexToBytes("b617318655057264e28bc0b6fb378c8e")
        assertArrayEquals(expected, result)
    }

    @Test fun `hmacSha1Truncated always returns 16 bytes`() {
        val result = TwampCrypto.hmacSha1Truncated(ByteArray(16), ByteArray(100))
        assertEquals(16, result.size)
    }

    @Test fun `hmacSha1Truncated different keys produce different results`() {
        val data = ByteArray(32) { it.toByte() }
        val r1 = TwampCrypto.hmacSha1Truncated(ByteArray(16) { 1 }, data)
        val r2 = TwampCrypto.hmacSha1Truncated(ByteArray(16) { 2 }, data)
        assertFalse(r1.contentEquals(r2))
    }

    // ── Round-trip (encrypt → decrypt) ────────────────────────────────────────

    @Test fun `AES-ECB round-trip with random-like data`() {
        val key = hexToBytes("0f1571c947d9e8590cb7add6af7f6798")
        val plain = ByteArray(16) { (it * 13 + 7).toByte() }
        val cipher = TwampCrypto.encryptAesEcb(key, plain)
        assertFalse(cipher.contentEquals(plain), "ciphertext must differ from plaintext")
        assertArrayEquals(plain, TwampCrypto.decryptAesEcb(key, cipher))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
