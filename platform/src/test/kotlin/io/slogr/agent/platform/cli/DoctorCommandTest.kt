package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.testing.test
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

class DoctorCommandTest {

    private fun makeCtx(): CliContext {
        val creds = mockk<CredentialStore>(relaxed = true)
        every { creds.isConnected() } returns false
        every { creds.load() }        returns null
        return CliContext(
            config                 = AgentConfig(),
            engine                 = mockk<MeasurementEngine>(relaxed = true),
            credentialStore        = creds,
            scheduleStore          = mockk<ScheduleStore>(relaxed = true),
            otlpExporter           = mockk<OtlpExporter>(relaxed = true),
            icmpPingProbe          = mockk<IcmpPingProbe>(relaxed = true),
            tcpConnectProbe        = mockk<TcpConnectProbe>(relaxed = true),
            tracerouteOrchestrator = mockk<TracerouteOrchestrator>(relaxed = true)
        )
    }

    // ── R2-DOC-01: All checks pass → exit 0, "All checks passed." ─────────────

    @Test
    fun `R2-DOC-01 all checks pass exits 0 and prints All checks passed`() {
        val doctor = DoctorCommand(
            ctx          = makeCtx(),
            jniChecker   = { true },
            capChecker   = { true },
            tlsChecker   = { true },
            airGapChecker = { false },
            isLinux      = true
        )
        val result = doctor.test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("All checks passed"), result.output)
    }

    // ── R2-DOC-02: CAP_NET_RAW missing → show setcap remediation ──────────────

    @Test
    fun `R2-DOC-02 CAP_NET_RAW missing shows setcap remediation and exits non-zero`() {
        val doctor = DoctorCommand(
            ctx          = makeCtx(),
            jniChecker   = { true },
            capChecker   = { false },
            tlsChecker   = { true },
            airGapChecker = { false },
            isLinux      = true
        )
        val result = doctor.test("")
        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("setcap"), result.output)
    }

    // ── R2-DOC-03: JNI library not found → show library path remediation ──────

    @Test
    fun `R2-DOC-03 JNI missing shows libslogr-native remediation and exits non-zero`() {
        val doctor = DoctorCommand(
            ctx          = makeCtx(),
            jniChecker   = { false },
            capChecker   = { true },
            tlsChecker   = { true },
            airGapChecker = { false },
            isLinux      = true
        )
        val result = doctor.test("")
        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("libslogr-native.so"), result.output)
    }

    // ── R2-DOC-04: Air-gapped → TLS check skipped, non-TLS checks still run ───

    @Test
    fun `R2-DOC-04 air-gapped skips TLS check and passes when other checks OK`() {
        val doctor = DoctorCommand(
            ctx          = makeCtx(),
            jniChecker   = { true },
            capChecker   = { true },
            tlsChecker   = { false },   // would fail if called
            airGapChecker = { true },   // air-gapped: TLS check skipped
            isLinux      = true
        )
        val result = doctor.test("")
        // TLS check is skipped → no TLS failure → exit 0
        assertEquals(0, result.statusCode)
        assertTrue(result.output.lowercase().contains("air-gapped"), result.output)
    }

    // ── Non-Linux: CAP_NET_RAW check is skipped ────────────────────────────────

    @Test
    fun `non-Linux skips CAP_NET_RAW check entirely`() {
        val doctor = DoctorCommand(
            ctx          = makeCtx(),
            jniChecker   = { true },
            capChecker   = { false },   // would fail if called on Linux
            tlsChecker   = { true },
            airGapChecker = { false },
            isLinux      = false        // Windows
        )
        val result = doctor.test("")
        assertEquals(0, result.statusCode)  // cap check not invoked
    }
}
