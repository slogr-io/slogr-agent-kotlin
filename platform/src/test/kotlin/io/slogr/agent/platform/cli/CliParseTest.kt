package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.testing.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.platform.config.AgentConfig
import io.slogr.agent.platform.otlp.OtlpExporter
import io.slogr.agent.platform.scheduler.ScheduleStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliParseTest {

    private fun makeCtx(): CliContext {
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val creds  = mockk<CredentialStore>(relaxed = true)
        every { creds.isConnected() } returns false
        every { creds.load() } returns null
        return CliContext(
            config               = AgentConfig(),
            engine               = engine,
            credentialStore      = creds,
            scheduleStore        = mockk<ScheduleStore>(relaxed = true),
            otlpExporter         = mockk<OtlpExporter>(relaxed = true),
            icmpPingProbe        = mockk<IcmpPingProbe>(relaxed = true),
            tcpConnectProbe      = mockk<TcpConnectProbe>(relaxed = true),
            tracerouteOrchestrator = mockk<TracerouteOrchestrator>(relaxed = true)
        )
    }

    // ── version ───────────────────────────────────────────────────────────────

    @Test
    fun `version command runs without error`() {
        val result = SlogrCli(makeCtx()).test("version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("slogr-agent"), result.output)
    }

    // ── status (disconnected) ─────────────────────────────────────────────────

    @Test
    fun `status command shows Disconnected when no credential`() {
        val result = SlogrCli(makeCtx()).test("status")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Disconnected"), result.output)
    }

    // ── setup-asn ─────────────────────────────────────────────────────────────

    @Test
    fun `setup-asn with missing db shows instructions`() {
        val result = SlogrCli(makeCtx()).test("setup-asn --db-path /nonexistent/GeoLite2-ASN.mmdb")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not found"), result.output)
    }

    // ── Invalid args → exit code 2 ────────────────────────────────────────────

    @Test
    fun `unknown command returns non-zero exit code`() {
        val result = SlogrCli(makeCtx()).test("thisdoesnotexist")
        assertNotEquals(0, result.statusCode)
    }

    // ── disconnect when not connected ─────────────────────────────────────────

    @Test
    fun `disconnect when not connected prints not connected`() {
        val result = SlogrCli(makeCtx()).test("disconnect")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("not connected"), result.output)
    }
}
