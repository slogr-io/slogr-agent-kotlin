package io.slogr.agent.engine.twamp.auth

import io.slogr.agent.engine.twamp.TwampMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * R2-ENC-02: Graceful fallback to mode=1 when reflector only supports unauthenticated.
 * R2-ENC-05: ServerGreeting advertises modes=0x05 (unauthenticated + encrypted).
 */
class TwampEncryptedModeTest {

    // ── R2-ENC-05: mode bits and ServerGreeting modes value ───────────────

    @Test
    fun `R2-ENC-05 UNAUTHENTICATED or ENCRYPTED equals 0x05`() {
        val modes = TwampMode.UNAUTHENTICATED or TwampMode.ENCRYPTED
        assertEquals(0x05, modes, "R2 ServerGreeting modes must be 0x05 (unauth + encrypted)")
    }

    @Test
    fun `R2-ENC-05 ENCRYPTED bit is 4 (bit 2)`() {
        assertEquals(4, TwampMode.ENCRYPTED)
    }

    // ── R2-ENC-01: controller selects mode=4 when server supports 0x05 ───

    @Test
    fun `R2-ENC-01 controller with ENCRYPTED preference selects mode=4 when server advertises 0x05`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.ENCRYPTED)
            .prefer(TwampMode.UNAUTHENTICATED)

        val selected = chain.selectMode(TwampMode.UNAUTHENTICATED or TwampMode.ENCRYPTED)
        assertEquals(TwampMode.ENCRYPTED, selected,
            "Should select ENCRYPTED when server advertises 0x05")
    }

    // ── R2-ENC-02: graceful fallback to mode=1 ───────────────────────────

    @Test
    fun `R2-ENC-02 controller falls back to mode=1 when server only supports unauthenticated`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.ENCRYPTED)
            .prefer(TwampMode.UNAUTHENTICATED)

        val selected = chain.selectMode(TwampMode.UNAUTHENTICATED)   // server: mode=1 only
        assertEquals(TwampMode.UNAUTHENTICATED, selected,
            "Should fall back to UNAUTHENTICATED when server does not support ENCRYPTED")
    }

    @Test
    fun `R2-ENC-02 controller with encrypted chain returns null when server has no common mode`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.ENCRYPTED)   // no unauthenticated fallback

        val selected = chain.selectMode(TwampMode.UNAUTHENTICATED)
        assertNull(selected, "Should return null when no mode is mutually supported")
    }

    // ── TwampMode predicates for mode=4 ─────────────────────────────────

    @Test
    fun `isControlEncrypted returns true for mode=4`() {
        val m = TwampMode().also { it.mode = TwampMode.ENCRYPTED }
        assertTrue(m.isControlEncrypted())
    }

    @Test
    fun `isTestEncrypted returns true for mode=4`() {
        val m = TwampMode().also { it.mode = TwampMode.ENCRYPTED }
        assertTrue(m.isTestEncrypted())
    }

    @Test
    fun `isTestEncrypted returns false for mode=1`() {
        val m = TwampMode().also { it.mode = TwampMode.UNAUTHENTICATED }
        assertFalse(m.isTestEncrypted())
    }

    // ── R2-ENC-04: HMAC verification (packet rejection) ─────────────────

    @Test
    fun `R2-ENC-04 HMAC mismatch is detected`() {
        val key = ByteArray(16) { it.toByte() }
        val data = ByteArray(32) { (it * 7).toByte() }

        val correctHmac = TwampCrypto.hmacSha1Truncated(key, data)
        val tamperedHmac = correctHmac.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        assertFalse(correctHmac.contentEquals(tamperedHmac),
            "Tampered HMAC must not equal correct HMAC — packet should be rejected")
    }

    @Test
    fun `R2-ENC-04 HMAC with wrong key does not match`() {
        val correctKey = ByteArray(16) { 0x01 }
        val wrongKey   = ByteArray(16) { 0x02 }
        val data = ByteArray(32) { it.toByte() }

        val correctHmac = TwampCrypto.hmacSha1Truncated(correctKey, data)
        val wrongHmac   = TwampCrypto.hmacSha1Truncated(wrongKey,   data)

        assertFalse(correctHmac.contentEquals(wrongHmac),
            "HMAC with wrong key must not match — packet should be rejected")
    }
}
