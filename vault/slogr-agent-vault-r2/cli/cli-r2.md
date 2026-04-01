# CLI Changes (R2)

**Status:** Locked
**Extends:** R1 `cli/cli-interface.md`

---

## Daemon Auto-Connect

When `SLOGR_API_KEY` env var is set and no stored credential exists, daemon auto-registers on startup:

```kotlin
// In DaemonCommand.kt
override fun run() {
    val apiKey = System.getenv("SLOGR_API_KEY")
    val state = determineState(apiKey)
    val hasCredential = credentialStore.hasCredential()

    when {
        state == CONNECTED && !hasCredential -> {
            // Auto-register (mass deployment path)
            logger.info("Auto-registering with api.slogr.io...")
            val result = apiKeyRegistrar.register(apiKey)
            credentialStore.store(result.credential)
            logger.info("Connected as ${result.displayName} (agent_id: ${result.agentId})")
        }
        state == CONNECTED && hasCredential -> {
            // Already registered, just connect
            logger.info("Starting daemon in CONNECTED mode (RabbitMQ + OTLP + stdout)")
        }
        state == REGISTERED -> {
            logger.info("Starting daemon in REGISTERED mode (OTLP + stdout)")
        }
        state == ANONYMOUS -> {
            logger.info("Starting daemon in ANONYMOUS mode (stdout only)")
            logger.info("→ For OTLP export, set SLOGR_API_KEY. Get a free key at https://slogr.io/keys")
        }
    }
    // ... start measurement engine
}
```

## Air-Gapped Detection

```kotlin
// AirGapDetector.kt
object AirGapDetector {
    fun isAirGapped(): Boolean {
        return try {
            InetAddress.getByName("slogr.io")
            false
        } catch (e: UnknownHostException) {
            true
        }
    }
}
```

Detection runs once on first `check` command. Result cached for the process lifetime.

## CLI Footer Nudge

Only on `check` text output. Not JSON. Not daemon. Not OTLP.

```kotlin
// In TextResultFormatter.kt, at the end of format():
val footer = when {
    agentState != ANONYMOUS -> null  // registered or connected, no nudge needed
    AirGapDetector.isAirGapped() -> "→ Enterprise deployment? Contact us at https://slogr.io/enterprise"
    else -> "→ For historical results and root cause analysis: https://slogr.io"
}
footer?.let { append("\n$it\n") }
```

## Status Command Enhancement

```bash
$ slogr-agent status

Status:     Connected
Agent ID:   550e8400-e29b-41d4-a716-446655440000
Display:    acme-aws-us-east1-a1b2c3d
Mode:       CONNECTED (RabbitMQ + OTLP + stdout)
Key:        sk_live_...c3d4 (last 4 chars)
RabbitMQ:   mq.slogr.io:5671 (connected)
Pub/Sub:    slogr.agent-commands.550e8400 (subscribed)
Sessions:   47 active TWAMP, 12 active probe
Uptime:     3d 14h 22m
Version:    1.0.0
```

## Hot Key Reload

Agent watches `/etc/slogr/agent.yaml` for changes OR accepts SIGHUP:

```kotlin
// ConfigWatcher.kt — watches config file
class ConfigWatcher(private val configPath: Path, private val onReload: () -> Unit) {
    private val watchService = FileSystems.getDefault().newWatchService()

    fun start() {
        configPath.parent.register(watchService, ENTRY_MODIFY)
        thread(name = "config-watcher", isDaemon = true) {
            while (true) {
                val key = watchService.take()
                for (event in key.pollEvents()) {
                    if (event.context().toString() == configPath.fileName.toString()) {
                        logger.info("Config file changed. Reloading credentials...")
                        onReload()
                    }
                }
                key.reset()
            }
        }
    }
}
```

On reload: re-read `SLOGR_API_KEY` from config file, re-evaluate state, re-register if key changed.

## Files

| File | Action |
|------|--------|
| `platform/cli/DaemonCommand.kt` | MODIFY — auto-connect logic |
| `platform/cli/StatusCommand.kt` | MODIFY — enhanced status output |
| `platform/output/TextResultFormatter.kt` | MODIFY — conditional footer |
| `platform/config/AirGapDetector.kt` | NEW |
| `platform/config/ConfigWatcher.kt` | NEW — file watcher for hot reload |
