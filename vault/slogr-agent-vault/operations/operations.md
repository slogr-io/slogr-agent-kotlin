---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
---

# Logging

## Format

Structured JSON in daemon mode. Human-readable text in CLI check mode.

```json
{"ts":"2026-03-30T12:00:00Z","level":"INFO","module":"scheduler","msg":"session started","path_id":"550e8400...","target":"10.0.1.5","profile":"voip"}
```

## Levels

| Level | When |
|-------|------|
| ERROR | Unrecoverable failures: JNI crash, credential corruption, registration failure |
| WARN | Recoverable issues: RabbitMQ disconnect (will retry), TWAMP session timeout, ASN DB missing |
| INFO | Normal operations: session started/completed, schedule updated, connected/disconnected |
| DEBUG | Detailed: packet-level timing, JNI call traces, JSON payloads (redacted) |

Default: INFO for daemon, WARN for check mode. Override with `--log-level` or `SLOGR_LOG_LEVEL`.

## Redaction

Never log:
- JWT tokens (log last 8 chars only: `...abc12345`)
- API keys
- Bootstrap tokens
- RabbitMQ passwords
- Key secrets for authenticated TWAMP

Always log:
- Agent ID
- Path ID
- Target IP (measurement target — this is not sensitive)
- Error messages and stack traces

## Output

- Daemon mode: stdout (captured by journald/Docker log driver). JSON format.
- Check mode: stderr for logs, stdout for results. Text format.
- Log rotation: handled by journald/Docker. If writing to file (standalone), use Logback with 50 MB / 3 rotations.

## Library

SLF4J + Logback. Configure via `logback.xml` bundled in the JAR.

---
---

# Upgrade Lifecycle

## Upgrade Flow (via Pub/Sub command)

```
1. Receive upgrade command: { version, download_url, checksum, restart_mode }
2. Validate download_url is on releases.slogr.io
3. Download binary to /opt/slogr/bin/slogr-agent-{version}.jar.tmp
4. Compute SHA-256 of downloaded file
5. Compare to checksum in command — REJECT if mismatch
6. Stop scheduler (no new sessions)
7. Wait for in-flight sessions (30s timeout)
8. Flush WAL to RabbitMQ (10s timeout)
9. Publish upgrade ACK to slogr.agent-responses
10. Move current binary to slogr-agent.jar.bak
11. Move new binary to slogr-agent.jar
12. Exit with code 0
13. Systemd restarts the service with the new binary
14. On boot: publish reconnect announcement with new version
```

## Rollback

If the new binary fails to start (crashes within 30 seconds, 3 times):

Systemd `StartLimitIntervalSec=120`, `StartLimitBurst=3`. After 3 failures in 120 seconds, systemd stops restarting.

Manual rollback:
```bash
sudo cp /opt/slogr/bin/slogr-agent.jar.bak /opt/slogr/bin/slogr-agent.jar
sudo systemctl restart slogr-agent
```

Future: Automatic rollback (detect crash loop, restore `.bak`, report failure to SaaS).

## Systemd Service File

```ini
[Unit]
Description=Slogr Network Measurement Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=slogr
Group=slogr
ExecStart=/usr/bin/slogr-agent daemon
Restart=always
RestartSec=5
TimeoutStopSec=45
AmbientCapabilities=CAP_NET_RAW CAP_NET_BIND_SERVICE
Environment=SLOGR_DATA_DIR=/opt/slogr/data
Environment=SLOGR_NATIVE_DIR=/opt/slogr/lib
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```
