# Registration (R2)

**Status:** Locked
**Replaces:** R1 `integration/registration-and-otlp.md` (bootstrap token model)

---

## Overview

R2 replaces bootstrap tokens with API keys. One env var (`SLOGR_API_KEY`), one endpoint (`POST /v1/agents`), two key types (`sk_free_*`, `sk_live_*`).

## Key Types

| Prefix | State | Capabilities |
|--------|-------|-------------|
| None | ANONYMOUS | check + daemon stdout only |
| `sk_free_*` | REGISTERED | + OTLP export to any endpoint |
| `sk_live_*` | CONNECTED | + RabbitMQ + Pub/Sub + full SaaS |

## Registration Flow (sk_live_* — CONNECTED)

### Explicit Path (interactive operator)

```bash
slogr-agent connect --api-key sk_live_abc123
```

### Implicit Path (mass deployment / Docker / K8s)

```bash
SLOGR_API_KEY=sk_live_abc123 slogr-agent daemon
# Agent detects key + no stored credential → auto-registers
```

### Sequence

1. Agent reads `SLOGR_API_KEY` from env var or `--api-key` flag
2. Validate format locally: must start with `sk_live_`
3. Check for existing credential at `/var/lib/slogr/.credential`
   - If exists and not expired → skip registration, connect directly
   - If not exists → proceed with registration
4. Gather machine identity via `MachineIdentity.kt`
5. Call `POST https://api.slogr.io/v1/agents`:
   - `Authorization: Bearer sk_live_abc123`
   - Body (16 fields):
     ```json
     {
       "machine_fingerprint": "SHA256(mac_address + hostname)",
       "cloud": "aws",
       "region": "us-east-1",
       "instance_id": "i-0a1b2c3d",
       "agent_version": "1.0.0",
       "public_ip": "203.0.113.1",
       "private_ip": "10.0.1.5",
       "hostname": "ip-10-0-1-5",
       "os_name": "linux",
       "os_version": "Ubuntu 22.04",
       "os_arch": "amd64",
       "mac_address": "02:42:ac:11:00:02",
       "runtime": "jvm",
       "runtime_version": "21.0.1",
       "native_mode": true,
       "cpu_cores": 4,
       "memory_mb": 8192
     }
     ```
6. SaaS validates key → extracts `tenant_id` from key scope
7. SaaS checks machine_fingerprint:
   - Existing agent with same fingerprint for this tenant → reactivate, return same `agent_id`
   - No match → create new agent record
8. SaaS returns:
   ```json
   {
     "agent_id": "550e8400-...",
     "tenant_id": "aaaaaaaa-...",
     "display_name": "acme-aws-us-east1-a1b2c3d",
     "credential": "<signed JWT>",
     "rabbitmq_host": "mq.slogr.io",
     "rabbitmq_port": 5671,
     "pubsub_subscription": "slogr.agent-commands.550e8400-..."
   }
   ```
9. Agent stores credential via `EncryptedCredentialStore`
10. Agent connects to RabbitMQ and Pub/Sub
11. Print: `Connected as acme-aws-us-east1-a1b2c3d (agent_id: 550e8400-...)`

### Failure Modes

| Failure | Agent behavior |
|---------|---------------|
| Invalid key format | Exit with code 4. Message: "Invalid key format. Get a valid key at slogr.io" |
| 401 Unauthorized (bad key) | Log error. Start in ANONYMOUS mode. |
| 403 Forbidden (key revoked) | Log error. Start in ANONYMOUS mode. |
| Network error (timeout, DNS fail) | Retry with exponential backoff (3s, 6s, 12s, 24s, 48s, 60s cap). Run in ANONYMOUS mode while retrying. |
| Machine fingerprint collision | SaaS creates new agent_id (should be extremely rare). |

## Free Key Validation (sk_free_* — REGISTERED)

Free keys do NOT trigger `/v1/agents` registration. The agent validates the key and starts OTLP export:

1. Read `SLOGR_API_KEY` from env var
2. Validate format locally: must start with `sk_free_`
3. Check cache at `/var/lib/slogr/key_validation.json`:
   - If cached result exists and < 24 hours old → use cached result
   - If no cache or expired → proceed with validation
4. Call `GET https://api.slogr.io/v1/keys/validate`:
   - `Authorization: Bearer sk_free_abc123`
   - **200 OK response:**
     ```json
     {
       "valid": true,
       "key_type": "free",
       "tenant_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
     }
     ```
     Cache as: `{ "valid": true, "key_type": "free", "tenant_id": "aaaaaaaa-...", "validated_at": "2026-03-31T12:00:00Z" }`
     Start REGISTERED.
   - **401 response:**
     ```json
     {
       "valid": false,
       "error": "key_revoked"
     }
     ```
     Cache as: `{ "valid": false, "validated_at": "2026-03-31T12:00:00Z" }`
     Log warning, start ANONYMOUS.
   - **Network error** → trust the format, start REGISTERED (supports air-gapped). No cache written.
   - Cache location: `/var/lib/slogr/key_validation.json`
   - Cache TTL: 24 hours. Re-validate after expiry.
   - The `tenant_id` from the cache is used to stamp OTLP resource attributes (see `otlp-gate.md`).
5. If REGISTERED: OTLP export enabled. No RabbitMQ, no Pub/Sub, no SaaS registration.

## Mass Deployment

One key per tenant. All machines in a deployment use the same `SLOGR_API_KEY`. Each machine gets a unique `agent_id` based on its `machine_fingerprint`. IT admin pushes the key via Ansible/Puppet/SCCM/Intune/Jamf/Terraform.

## Key Rotation

1. Admin generates new key in SaaS dashboard
2. Admin pushes new key to all agents via deployment tool
3. Agent detects new key (config file watch or SIGHUP) → triggers re-registration
4. Admin revokes old key in SaaS dashboard
5. Agents still using old key: JWT refresh fails → ANONYMOUS mode → WAL buffer
6. As new key rolls out, agents reconnect automatically

Agent must support key change without restart: watch `/etc/slogr/agent.yaml` for changes, or accept SIGHUP to reload.

## Disconnect

```bash
slogr-agent disconnect
```

1. Delete stored credential from `EncryptedCredentialStore`
2. Close RabbitMQ and Pub/Sub connections
3. Agent continues running in ANONYMOUS mode (stdout only)
4. Print: `Disconnected. Agent continues in local mode.`

## Files

| File | Action |
|------|--------|
| `integration/registration/ApiKeyRegistrar.kt` | NEW — replaces `BootstrapRegistrar.kt` |
| `integration/registration/FreeKeyValidator.kt` | NEW — `GET /v1/keys/validate` + local cache |
| `integration/registration/KeyValidationCache.kt` | NEW — reads/writes `/var/lib/slogr/key_validation.json` |
| `platform/config/AgentConfig.kt` | MODIFY — add `SLOGR_API_KEY` env var reading |
| `platform/config/ConfigWatcher.kt` | NEW — watches config file for key changes, triggers reload |
| `platform/cli/ConnectCommand.kt` | MODIFY — use `ApiKeyRegistrar` instead of `BootstrapRegistrar` |
| `platform/cli/DaemonCommand.kt` | MODIFY — auto-register when `SLOGR_API_KEY` present + no credential |
| R1 `BootstrapRegistrar.kt` | DELETE — no longer used |
| R1 `InteractiveRegistrar.kt` | KEEP — may be useful for interactive key entry |
