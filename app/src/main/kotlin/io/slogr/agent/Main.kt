package io.slogr.agent

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.TwampAuthMode
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.MeasurementEngineImpl
import io.slogr.agent.engine.asn.Ip2AsnResolver
import io.slogr.agent.engine.asn.MaxMindAsnResolver
import io.slogr.agent.engine.asn.SwappableAsnResolver
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.native.JavaUdpTransport
import io.slogr.agent.platform.asn.AsnDatabaseUpdater
import io.slogr.agent.platform.cli.CliContext
import io.slogr.agent.platform.cli.SlogrCli
import io.slogr.agent.platform.config.AgentConfig
import io.slogr.agent.platform.credential.EncryptedCredentialStore
import io.slogr.agent.platform.otlp.OtlpExporter
import io.slogr.agent.platform.scheduler.ScheduleStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.util.Properties
import java.util.UUID

fun main(args: Array<String>) {
    val code = run(args)
    if (code != 0) System.exit(code)
}

/**
 * Testable entry point — returns the process exit code.
 *
 * Exit codes:
 * - 0  Success
 * - 1  General / internal error
 * - 2  Invalid arguments or unknown command
 * - 3  Target unreachable (set by CheckCommand)
 */
fun run(args: Array<String>): Int {
    val config         = AgentConfig()
    val credentialStore = EncryptedCredentialStore(config.dataDir)
    val scheduleStore   = ScheduleStore(config.dataDir)
    val agentId         = credentialStore.load()?.agentId ?: UUID.randomUUID()
    val otlpExporter    = OtlpExporter(config.otlpEndpoint, agentId)

    // ── ASN resolver wiring ────────────────────────────────────────────────
    val adapter = JavaUdpTransport()
    val agentVersion = loadAgentVersion()
    val swappableResolver = SwappableAsnResolver()
    val asnDatabaseUpdater: AsnDatabaseUpdater?

    val maxMindResolver = config.asnDbPath
        ?.let { MaxMindAsnResolver(File(it)).takeIf { r -> r.isAvailable() } }

    if (maxMindResolver != null) {
        swappableResolver.swap(maxMindResolver)
        asnDatabaseUpdater = null  // MaxMind explicitly configured, no auto-download
    } else {
        val updater = AsnDatabaseUpdater(config.dataDir, agentId, agentVersion)
        val tsvFile: File? = runBlocking { updater.ensureDatabase() }
        val ip2asn: Ip2AsnResolver? = if (tsvFile != null) {
            Ip2AsnResolver.fromFile(tsvFile)
        } else {
            Ip2AsnResolver.fromResource()
        }
        if (ip2asn != null) swappableResolver.swap(ip2asn)
        asnDatabaseUpdater = updater
    }

    // Client-only commands (check) don't need the reflector — skip binding port 862
    // so the check command works alongside a running daemon on the same machine.
    val needsReflector = args.firstOrNull() != "check"

    val engineLazy = lazy {
        MeasurementEngineImpl(
            adapter             = adapter,
            asnResolver         = swappableResolver,
            agentId             = agentId,
            reflectorListenPort = config.defaultTwampPort,
            startReflector      = needsReflector,
            testPort            = config.testUdpPort
        )
    }

    // Delegate that only starts the engine on first measurement call.
    // DaemonCommand calls start() to ensure the TWAMP reflector is listening
    // before any remote controller tries to connect.
    val engineProxy = object : MeasurementEngine {
        override fun start() { engineLazy.value }  // force eager initialization

        override suspend fun measure(
            target: InetAddress, targetPort: Int, profile: SlaProfile,
            traceroute: Boolean, authMode: TwampAuthMode, keyId: String?
        ): MeasurementBundle = engineLazy.value.measure(target, targetPort, profile, traceroute, authMode, keyId)

        override suspend fun twamp(
            target: InetAddress, targetPort: Int, profile: SlaProfile,
            authMode: TwampAuthMode, keyId: String?
        ): MeasurementResult = engineLazy.value.twamp(target, targetPort, profile, authMode, keyId)

        override suspend fun traceroute(
            target: InetAddress, maxHops: Int, probesPerHop: Int, timeoutMs: Int, mode: TracerouteMode?
        ): TracerouteResult = engineLazy.value.traceroute(target, maxHops, probesPerHop, timeoutMs, mode)

        override fun shutdown() {
            if (engineLazy.isInitialized()) engineLazy.value.shutdown()
        }
    }

    val tracerouteOrchestrator = TracerouteOrchestrator(adapter, swappableResolver)

    val ctx = CliContext(
        config                 = config,
        engine                 = engineProxy,
        credentialStore        = credentialStore,
        scheduleStore          = scheduleStore,
        otlpExporter           = otlpExporter,
        icmpPingProbe          = IcmpPingProbe(adapter),
        tcpConnectProbe        = TcpConnectProbe(),
        tracerouteOrchestrator = tracerouteOrchestrator,
        asnDatabaseUpdater     = asnDatabaseUpdater,
        swappableAsnResolver   = swappableResolver
    )

    return try {
        SlogrCli(ctx).parse(args.toList())
        0
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: UsageError) {
        System.err.println(e.message ?: "Usage error")
        2
    } catch (e: CliktError) {
        System.err.println(e.message)
        1
    } finally {
        engineProxy.shutdown()
        otlpExporter.shutdown()
    }
}

private fun loadAgentVersion(): String {
    val props = Properties()
    val stream = object {}.javaClass.getResourceAsStream("/version.properties")
    stream?.use { props.load(it) }
    return props.getProperty("version", "unknown")
}
