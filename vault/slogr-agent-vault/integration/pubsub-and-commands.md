---
status: locked
version: 1.0
depends-on:
  - architecture/data-model
  - security/input-validation
claude-code-context:
  - "Read when implementing command handling"
  - "Cross-reference with SaaS vault: layer25/command-payloads.md"
---

# Pub/Sub Subscriber

## Overview

Polls the agent's GCP Pub/Sub pull subscription for commands. Active only in connected mode. The subscription is created by the BFF at registration time and filtered to this agent's `agent_id`.

## Subscription

Name: `slogr.agent-commands.{agent_id}` (from `AgentCredential.pubsubSubscription`)
Filter: `attributes.agent_id = "{agent_id}"` (server-side filter — agent never sees other agents' commands)

## Polling

Pull every 5 seconds. Max messages per pull: 10. ACK each message after the command handler completes (success or failure).

Library: `com.google.cloud:google-cloud-pubsub` (GCP Pub/Sub client library for Java/Kotlin).

Auth: The agent authenticates to GCP using the agent JWT. The BFF must configure the Pub/Sub subscription to accept this JWT (or the registration response includes a GCP service account key — design detail to confirm with backend team).

## Command Envelope Validation

Every command is validated before dispatch:

```kotlin
data class CommandEnvelope(
    val commandId: UUID,
    val commandType: String,
    val agentId: UUID,
    val tenantId: UUID,
    val issuedAt: Instant,
    val payload: JsonNode
)
```

| Check | Action on Failure |
|-------|-------------------|
| Valid JSON | NACK message, log error |
| `commandType` is known enum | Respond `status: failed`, error: "unknown command type" |
| `agentId` matches this agent | Drop silently (should never happen due to subscription filter) |
| `tenantId` matches this agent | Respond `status: failed`, error: "tenant mismatch" |
| Payload validates per command type | Respond `status: failed`, error describing validation failure |

## Command Response

After handling any command, publish response to `slogr.agent-responses`:

```kotlin
data class CommandResponse(
    val commandId: UUID,
    val agentId: UUID,
    val tenantId: UUID,
    val status: String,                      // "acked" or "failed"
    val respondedAt: Instant,
    val result: Any? = null,
    val error: String? = null
)
```

---

# Command Handlers

## run_test

Run a single on-demand test and return the result.

```kotlin
suspend fun handleRunTest(payload: RunTestPayload): CommandResponse {
    val result = engine.measure(
        target = payload.targetIp,
        profile = profileRegistry.get(payload.profileName),
        traceroute = (payload.testType == "traceroute" || payload.testType == "both")
    )
    return CommandResponse(
        status = "acked",
        result = RunTestResult(
            testType = payload.testType,
            pathId = payload.targetPathId,
            p99Ms = result.twamp.fwdMaxRttMs,     // approximate p99
            p50Ms = result.twamp.fwdAvgRttMs,     // approximate p50
            lossPct = result.twamp.fwdLossPct,
            jitterMs = result.twamp.fwdJitterMs,
            completedAt = Instant.now()
        )
    )
}
```

## set_schedule

Replace the agent's test schedule. Persist to disk. Restart scheduler with new sessions.

```kotlin
suspend fun handleSetSchedule(payload: SetSchedulePayload): CommandResponse {
    val schedule = Schedule(
        sessions = payload.targets.map { target ->
            SessionConfig(
                pathId = target.pathId,
                targetIp = InetAddress.getByName(target.targetIp),
                profile = profileRegistry.get(payload.profileName) ?: defaultProfile,
                intervalSeconds = payload.intervalSeconds,
                tracerouteEnabled = (payload.testType != "twamp")
            )
        },
        receivedAt = Instant.now(),
        commandId = commandId
    )
    scheduler.updateSchedule(schedule)
    scheduleStore.persist(schedule)
    return CommandResponse(status = "acked")
}
```

## push_config

Apply configuration update immediately.

```kotlin
suspend fun handlePushConfig(payload: PushConfigPayload): CommandResponse {
    payload.twampPacketCount?.let { config.twampPacketCount = it }
    payload.tracerouteMaxHops?.let { config.tracerouteMaxHops = it }
    payload.reportingThresholdMs?.let { config.reportingThresholdMs = it }
    payload.bufferFlushIntervalS?.let { config.bufferFlushIntervalS = it }
    config.persist()
    return CommandResponse(status = "acked")
}
```

## upgrade

Download binary, verify checksum, swap, restart.

```kotlin
suspend fun handleUpgrade(payload: UpgradePayload): CommandResponse {
    // Validate download URL is on releases.slogr.io
    Validate.slogrDomain(payload.downloadUrl)

    // Download to temp file
    val tempFile = downloadBinary(payload.downloadUrl)

    // Verify SHA-256 checksum
    val actualChecksum = sha256(tempFile)
    if (actualChecksum != payload.checksum.removePrefix("sha256:")) {
        tempFile.delete()
        return CommandResponse(status = "failed", error = "checksum mismatch")
    }

    // Graceful drain
    scheduler.stopAcceptingNew()
    scheduler.waitForInFlight(timeout = 30.seconds)
    rabbitMqPublisher.flush()

    // Swap binary
    val currentBinary = Paths.get(System.getProperty("slogr.binary.path"))
    val backup = currentBinary.resolveSibling("slogr-agent.bak")
    Files.move(currentBinary, backup, REPLACE_EXISTING)
    Files.move(tempFile.toPath(), currentBinary, REPLACE_EXISTING)
    currentBinary.toFile().setExecutable(true)

    // ACK before restart
    val response = CommandResponse(status = "acked")
    publishResponse(response)

    // Restart (systemd will restart the service)
    exitProcess(0)
}
```

## deregister

Drain buffer, ACK, exit.

```kotlin
suspend fun handleDeregister(payload: DeregisterPayload): CommandResponse {
    scheduler.stopAll()
    walBuffer.flushToRabbitMq(timeout = 10.seconds)
    credentialStore.delete()
    val response = CommandResponse(status = "acked")
    publishResponse(response)
    exitProcess(0)
}
```

## Reconnect Announcement

On every boot after registration, before polling for commands:

```kotlin
val announcement = CommandResponse(
    commandId = null,
    agentId = credential.agentId,
    tenantId = credential.tenantId,
    status = "reconnect",
    respondedAt = Instant.now(),
    result = ReconnectResult(
        version = BuildInfo.version,
        currentSchedule = scheduleStore.load(),
        uptimeSeconds = 0
    )
)
publishToAgentResponses(announcement)
```
