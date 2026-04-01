package io.slogr.agent.engine.twamp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwampModeTest {

    private fun mode(flags: Int) = TwampMode().also { it.mode = flags }

    // ── Mode predicates ───────────────────────────────────────────────────────

    @Test fun `unauthenticated is not control-encrypted`() {
        assertFalse(mode(TwampMode.UNAUTHENTICATED).isControlEncrypted())
    }

    @Test fun `authenticated is control-encrypted`() {
        assertTrue(mode(TwampMode.AUTHENTICATED).isControlEncrypted())
    }

    @Test fun `encrypted is control-encrypted`() {
        assertTrue(mode(TwampMode.ENCRYPTED).isControlEncrypted())
    }

    @Test fun `mixed-mode is control-encrypted`() {
        assertTrue(mode(TwampMode.MIXED_MODE).isControlEncrypted())
    }

    @Test fun `unauthenticated is not test-encrypted`() {
        assertFalse(mode(TwampMode.UNAUTHENTICATED).isTestEncrypted())
    }

    @Test fun `mixed-mode is not test-encrypted (test side is unauth)`() {
        assertFalse(mode(TwampMode.MIXED_MODE).isTestEncrypted())
    }

    @Test fun `authenticated is test-authenticated`() {
        assertTrue(mode(TwampMode.AUTHENTICATED).isTestAuthenticated())
    }

    @Test fun `unauthenticated is not test-authenticated`() {
        assertFalse(mode(TwampMode.UNAUTHENTICATED).isTestAuthenticated())
    }

    @Test fun `isValidTwampMode true for single-bit modes`() {
        assertTrue(mode(TwampMode.UNAUTHENTICATED).isValidTwampMode())
        assertTrue(mode(TwampMode.AUTHENTICATED).isValidTwampMode())
        assertTrue(mode(TwampMode.ENCRYPTED).isValidTwampMode())
        assertTrue(mode(TwampMode.MIXED_MODE).isValidTwampMode())
    }

    @Test fun `isValidTwampMode false for multi-bit modes`() {
        assertFalse(mode(TwampMode.UNAUTHENTICATED or TwampMode.AUTHENTICATED).isValidTwampMode())
        assertFalse(mode(0).isValidTwampMode())
    }

    @Test fun `isModeSupported checks overlap with server modes`() {
        val m = mode(TwampMode.UNAUTHENTICATED)
        assertTrue(m.isModeSupported(TwampMode.UNAUTHENTICATED or TwampMode.AUTHENTICATED))
        assertFalse(m.isModeSupported(TwampMode.AUTHENTICATED))
    }

    @Test fun `setIndividualSessionControl toggles bit`() {
        val m = mode(TwampMode.UNAUTHENTICATED)
        assertFalse(m.isIndividualSessionControl())
        m.setIndividualSessionControl()
        assertTrue(m.isIndividualSessionControl())
    }

    @Test fun `pktTruncLength returns UNAUTH for unauthenticated`() {
        assertEquals(TwampConstants.PKT_TRUNC_UNAUTH, mode(TwampMode.UNAUTHENTICATED).pktTruncLength())
    }

    @Test fun `ALL_TWAMP_MODES covers exactly bits 0-3`() {
        assertEquals(0b1111, TwampMode.ALL_TWAMP_MODES)
    }

    // ── BUG-D fix: getTestSessionMode allocates separate IV arrays ────────────

    @Test fun `BUG-D - getTestSessionMode uses separate sendIv and receiveIv arrays`() {
        val ctrl = mode(TwampMode.UNAUTHENTICATED)
        val sid = SessionId(ipv4 = 0x7F000001, timestamp = 100L, randNumber = 1)
        val testMode = ctrl.getTestSessionMode(sid)
        // The two arrays must be different objects (not aliased)
        assertNotSame(testMode.sendIv, testMode.receiveIv,
            "BUG-D regression: sendIv and receiveIv must be independent byte arrays")
    }

    @Test fun `getTestSessionMode inherits parent mode flags`() {
        val ctrl = mode(TwampMode.UNAUTHENTICATED or TwampMode.INDIVIDUAL_SESSION_CONTROL)
        val sid = SessionId(ipv4 = 0x7F000001, timestamp = 100L, randNumber = 1)
        val testMode = ctrl.getTestSessionMode(sid)
        assertEquals(ctrl.mode, testMode.mode)
    }

    @Test fun `getTestSessionMode IVs are all-zero for unauthenticated`() {
        val ctrl = mode(TwampMode.UNAUTHENTICATED)
        val sid = SessionId(ipv4 = 0x7F000001, timestamp = 100L, randNumber = 1)
        val testMode = ctrl.getTestSessionMode(sid)
        assertArrayEquals(ByteArray(16), testMode.sendIv)
        assertArrayEquals(ByteArray(16), testMode.receiveIv)
    }
}
