package io.slogr.agent.engine.twamp.util

import kotlin.math.exp
import kotlin.math.round
import kotlin.random.Random

/**
 * NTP timestamp conversion and Poisson-distribution timing helpers.
 *
 * NTP epoch: 1 January 1900. The 64-bit NTP timestamp encodes:
 *   bits 63-32 (high): unsigned seconds since NTP epoch
 *   bits 31-0  (low):  fractional seconds (2^-32 resolution)
 *
 * The MSB of the seconds field selects the era:
 *   MSB=1 → era 0: 1900-2036
 *   MSB=0 → era 1: 2036-2104
 */
object TwampTimeUtil {

    // Java epoch (1 Jan 1970) relative to NTP epoch (1 Jan 1900) = 70 years in ms
    private const val BASETIME1: Long = -2_208_988_800_000L   // 1 Jan 1900 in Java ms
    private const val BASETIME0: Long =  2_085_978_496_000L   // 7 Feb 2036 in Java ms

    /**
     * Convert a 64-bit NTP timestamp to Java epoch milliseconds.
     */
    fun ntpToMillis(ntpTimestamp: Long): Long {
        val seconds  = (ntpTimestamp ushr 32) and 0xFFFFFFFFL
        val fraction = ntpTimestamp and 0xFFFFFFFFL
        val fracMs   = round(1000.0 * fraction / 0x100000000L).toLong()
        return if ((seconds and 0x80000000L) != 0L) {
            BASETIME1 + seconds * 1000L + fracMs
        } else {
            BASETIME0 + seconds * 1000L + fracMs
        }
    }

    /**
     * Convert Java epoch milliseconds to a 64-bit NTP timestamp.
     */
    fun millisToNtp(millis: Long): Long {
        val useBase1 = millis < BASETIME0
        val base = if (useBase1) millis - BASETIME1 else millis - BASETIME0
        var seconds  = base / 1000L
        val fraction = (base % 1000L) * 0x100000000L / 1000L
        if (useBase1) seconds = seconds or 0x80000000L
        return (seconds shl 32) or fraction
    }

    /** Returns the NTP timestamp for the current wall-clock time. */
    fun currentNtpTimestamp(): Long = millisToNtp(System.currentTimeMillis())

    /**
     * Converts an NTP-encoded timeout (same 64-bit format) to milliseconds.
     * Only the high 32 bits (seconds) and low 32 bits (fractions) are used.
     */
    fun ntpTimeoutToMillis(ntpTimeout: Long): Long {
        val seconds  = (ntpTimeout ushr 32) and 0xFFFFFFFFL
        val fraction = ntpTimeout and 0xFFFFFFFFL
        return seconds * 1000L + round(1000.0 * fraction / 0x100000000L).toLong()
    }

    /**
     * Compute the signed difference (later − earlier) in milliseconds between two
     * NTP timestamps. Returns a positive value if [laterNtp] > [earlierNtp].
     */
    fun ntpDiffMs(laterNtp: Long, earlierNtp: Long): Double {
        val diffNtp = laterNtp - earlierNtp
        val seconds  = (diffNtp shr 32).toDouble()
        val fraction = (diffNtp and 0xFFFFFFFFL).toDouble() / 0x100000000L
        return (seconds + fraction) * 1000.0
    }

    /**
     * Compute the signed difference in nanoseconds between two NTP timestamps.
     */
    fun ntpDiffNs(laterNtp: Long, earlierNtp: Long): Long =
        (ntpDiffMs(laterNtp, earlierNtp) * 1_000_000.0).toLong()

    /**
     * Generate a Poisson-distributed random interval with the given mean (ms).
     *
     * Uses the standard inverse-CDF method: X = -mean * ln(U) where U ~ Uniform(0,1).
     * The result is capped at [maxIntervalMs] when provided.
     *
     * @param meanMs   Mean inter-packet interval in milliseconds.
     * @param maxIntervalMs Optional cap; 0 means uncapped.
     */
    fun poissonIntervalMs(meanMs: Long, maxIntervalMs: Long = 0L): Long {
        if (meanMs <= 0L) return 0L
        // Scale down for numerical stability when mean > 100
        var multiplier = 1L
        var m = meanMs.toDouble()
        while (m > 100.0) {
            multiplier *= 10
            m /= 10.0
        }
        val L = exp(-m)
        var k = 0L
        var p = 1.0
        do {
            p *= Random.nextDouble()
            k++
        } while (p > L)
        val result = (k - 1) * multiplier
        return if (maxIntervalMs > 0L) minOf(result, maxIntervalMs) else result
    }
}
