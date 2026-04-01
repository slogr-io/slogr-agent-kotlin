package io.slogr.agent.platform.cli

import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.platform.config.AgentConfig
import io.slogr.agent.platform.otlp.OtlpExporter
import io.slogr.agent.platform.output.JsonResultFormatter
import io.slogr.agent.platform.output.ResultFormatter
import io.slogr.agent.platform.output.TextResultFormatter
import io.slogr.agent.platform.scheduler.ScheduleStore
import io.slogr.agent.platform.scheduler.TestScheduler

/**
 * Dependency container passed to all CLI commands.
 * Assembled in [Main.kt] using the real implementations.
 */
data class CliContext(
    val config: AgentConfig,
    val engine: MeasurementEngine,
    val credentialStore: CredentialStore,
    val scheduleStore: ScheduleStore,
    val otlpExporter: OtlpExporter,
    val icmpPingProbe: IcmpPingProbe,
    val tcpConnectProbe: TcpConnectProbe,
    val tracerouteOrchestrator: TracerouteOrchestrator,
    val textFormatter: ResultFormatter = TextResultFormatter(),
    val jsonFormatter: ResultFormatter = JsonResultFormatter()
)
