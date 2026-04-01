package io.slogr.agent.engine.twamp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwampConstantsTest {

    @Test fun `default port is 862`() {
        assertEquals(862, TwampConstants.DEFAULT_PORT)
    }

    @Test fun `PKT_TRUNC_UNAUTH is 27`() {
        // RFC 5357: unauthenticated reflector packet is 27 bytes smaller than sender
        assertEquals(27, TwampConstants.PKT_TRUNC_UNAUTH)
    }

    @Test fun `PKT_TRUNC_AUTH_ENC is 64`() {
        // RFC 5357: authenticated/encrypted reflector packet is 64 bytes smaller
        assertEquals(64, TwampConstants.PKT_TRUNC_AUTH_ENC)
    }

    @Test fun `Slogr magic is ASCII SLOGR`() {
        assertArrayEquals("SLOGR".toByteArray(Charsets.US_ASCII), TwampConstants.SLOGR_MAGIC)
    }

    @Test fun `FIX-1 - no DEFAULT_MAX_COUNT constant exists`() {
        // FIX-1: The Java agent had DEFAULT_MAX_COUNT = 32768 which caused
        // rejection of ServerGreetings from Cisco devices (which send count=65536).
        // The Kotlin port intentionally has no count ceiling — this test documents that.
        val fields = TwampConstants::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("MAX_COUNT", ignoreCase = true) },
            "FIX-1 regression: MAX_COUNT constant must not exist in TwampConstants")
    }

    @Test fun `MIN_COUNT is at least 1`() {
        assertTrue(TwampConstants.MIN_COUNT >= 1)
    }

    @Test fun `DEFAULT_COUNT meets MIN_COUNT`() {
        assertTrue(TwampConstants.DEFAULT_COUNT >= TwampConstants.MIN_COUNT)
    }
}
