package io.slogr.agent.engine.probe

import io.slogr.agent.contracts.ProbeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * R2-PROBE-01 through R2-PROBE-04: Probe mode classification.
 *
 * Verifies that [IcmpPingProbe.classify] correctly maps the (icmpSuccess, tcpSuccess)
 * pair to the appropriate [ProbeMode], preventing false "100% loss" alerts when ICMP
 * is blocked by firewalls but TCP is healthy.
 */
class ProbeModeTest {

    @Test
    fun `R2-PROBE-01 ICMP and TCP both succeed returns ICMP_AND_TCP`() {
        assertEquals(ProbeMode.ICMP_AND_TCP, IcmpPingProbe.classify(icmpSuccess = true,  tcpSuccess = true))
    }

    @Test
    fun `R2-PROBE-02 ICMP blocked TCP succeeds returns TCP_ONLY`() {
        assertEquals(ProbeMode.TCP_ONLY, IcmpPingProbe.classify(icmpSuccess = false, tcpSuccess = true))
    }

    @Test
    fun `R2-PROBE-03 ICMP succeeds TCP fails returns ICMP_ONLY`() {
        assertEquals(ProbeMode.ICMP_ONLY, IcmpPingProbe.classify(icmpSuccess = true,  tcpSuccess = false))
    }

    @Test
    fun `R2-PROBE-04 both fail returns BOTH_FAILED`() {
        assertEquals(ProbeMode.BOTH_FAILED, IcmpPingProbe.classify(icmpSuccess = false, tcpSuccess = false))
    }

    @Test
    fun `ProbeMode enum has exactly four values`() {
        assertEquals(4, ProbeMode.entries.size)
    }
}
