package io.slogr.agent.engine.twamp.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwampTimeUtilTest {

    // ── NTP epoch constants ───────────────────────────────────────────────────

    /** 1 Jan 1970 00:00:00 UTC in NTP seconds (era 0, MSB=1). */
    private val NTP_UNIX_EPOCH: Long = 2_208_988_800L shl 32

    // ── millisToNtp / ntpToMillis round-trip ─────────────────────────────────

    @Test fun `Unix epoch round-trips correctly`() {
        val millis = 0L
        val ntp = TwampTimeUtil.millisToNtp(millis)
        assertEquals(millis, TwampTimeUtil.ntpToMillis(ntp))
    }

    @Test fun `known timestamp round-trips correctly`() {
        val millis = 1_743_000_000_000L  // some time in 2025
        val ntp = TwampTimeUtil.millisToNtp(millis)
        assertEquals(millis, TwampTimeUtil.ntpToMillis(ntp))
    }

    @Test fun `era 0 timestamps (pre-2036) have MSB set in seconds`() {
        val millis = 1_000_000_000_000L  // 2001
        val ntp = TwampTimeUtil.millisToNtp(millis)
        val seconds = (ntp ushr 32) and 0xFFFFFFFFL
        assertNotEquals(0L, seconds and 0x80000000L, "Pre-2036 NTP seconds must have MSB set")
    }

    @Test fun `fractional second round-trip within 1ms tolerance`() {
        val millis = 1_700_000_000_500L  // .5 seconds fraction
        val ntp = TwampTimeUtil.millisToNtp(millis)
        val restored = TwampTimeUtil.ntpToMillis(ntp)
        assertTrue(kotlin.math.abs(restored - millis) <= 1L, "Round-trip error > 1ms: $restored vs $millis")
    }

    @Test fun `currentNtpTimestamp returns non-zero`() {
        val ntp = TwampTimeUtil.currentNtpTimestamp()
        assertNotEquals(0L, ntp)
    }

    @Test fun `currentNtpTimestamp is close to System currentTimeMillis`() {
        val before = System.currentTimeMillis()
        val ntp = TwampTimeUtil.currentNtpTimestamp()
        val after = System.currentTimeMillis()
        val restored = TwampTimeUtil.ntpToMillis(ntp)
        assertTrue(restored in before..after + 5L,
            "NTP timestamp $restored not in [$before, $after]")
    }

    // ── ntpTimeoutToMillis ────────────────────────────────────────────────────

    @Test fun `ntpTimeoutToMillis converts 1 second`() {
        val ntp = 1L shl 32
        assertEquals(1000L, TwampTimeUtil.ntpTimeoutToMillis(ntp))
    }

    @Test fun `ntpTimeoutToMillis converts 5 seconds`() {
        val ntp = 5L shl 32
        assertEquals(5000L, TwampTimeUtil.ntpTimeoutToMillis(ntp))
    }

    @Test fun `ntpTimeoutToMillis converts half second`() {
        val ntp = 0x80000000L  // 0.5 second in NTP fractions
        val ms = TwampTimeUtil.ntpTimeoutToMillis(ntp)
        assertTrue(ms in 499L..501L, "0.5s NTP fraction should be ~500ms, got $ms")
    }

    // ── poissonIntervalMs ─────────────────────────────────────────────────────

    @Test fun `zero mean returns zero`() {
        assertEquals(0L, TwampTimeUtil.poissonIntervalMs(0L))
    }

    @Test fun `negative mean returns zero`() {
        assertEquals(0L, TwampTimeUtil.poissonIntervalMs(-100L))
    }

    @Test fun `result is non-negative`() {
        repeat(50) {
            val v = TwampTimeUtil.poissonIntervalMs(100L)
            assertTrue(v >= 0L, "Poisson result must be non-negative, got $v")
        }
    }

    @Test fun `maxIntervalMs cap is respected`() {
        repeat(50) {
            val v = TwampTimeUtil.poissonIntervalMs(1000L, maxIntervalMs = 500L)
            assertTrue(v <= 500L, "Result $v exceeded maxIntervalMs=500")
        }
    }

    @Test fun `no cap when maxIntervalMs is zero`() {
        // With a very large mean and many samples, at least one should exceed 500ms
        // This is probabilistic — with mean=10000 and 100 samples it's virtually certain
        var anyAbove = false
        repeat(100) {
            if (TwampTimeUtil.poissonIntervalMs(10_000L, maxIntervalMs = 0L) > 500L) anyAbove = true
        }
        assertTrue(anyAbove, "With mean=10000, some samples should exceed 500ms when uncapped")
    }

    @Test fun `large mean does not overflow or produce negative results`() {
        // Large means trigger the scale-down path in the implementation
        repeat(20) {
            val v = TwampTimeUtil.poissonIntervalMs(100_000L)
            assertTrue(v >= 0L, "Large mean: result must be non-negative, got $v")
        }
    }
}
