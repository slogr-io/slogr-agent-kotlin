---
status: locked
version: 1.0
depends-on:
  - security/threat-model
---

# Credential Management

## Credential Types

| Credential | Lifetime | Storage | Rotation |
|------------|----------|---------|----------|
| Bootstrap token | 24 hours, single-use | Never stored — read from env var, used once, discarded | N/A — consumed on use |
| Agent JWT | Long-lived (months) | Encrypted local file | Re-issued via `push_config` command or manual `slogr connect` |
| RabbitMQ JWT | Short-lived (1 hour) | In-memory only | Agent refreshes from SaaS config endpoint before expiry |
| API key (Pro connect) | Long-lived | User-provided, exchanged for agent JWT during `slogr connect` | User rotates in SaaS UI |

## Local Credential Storage

Location: `$SLOGR_DATA_DIR/credential.enc` (default: `/opt/slogr/data/credential.enc`)

Encryption: AES-256-GCM. Key derived via PBKDF2 from a machine-identity seed (combination of machine-id from `/etc/machine-id` and a random salt stored alongside the encrypted file). This is not HSM-grade security — it prevents casual file reading. A root-level attacker on the same machine can extract the key. Defense in depth: file permissions 0600, owned by `slogr` user.

```kotlin
// Credential file structure
data class CredentialFile(
    val salt: ByteArray,           // 16 bytes, random, generated once
    val iv: ByteArray,             // 12 bytes, random per write
    val ciphertext: ByteArray,     // AES-256-GCM encrypted AgentCredential JSON
    val tag: ByteArray             // GCM auth tag
)
```

## RabbitMQ JWT Refresh

The RabbitMQ JWT is short-lived (1 hour). The agent refreshes it by calling:

```
GET https://api.slogr.io/api/v1/agents/rabbitmq-token
Authorization: Bearer <agent_jwt>
```

Returns a new RabbitMQ JWT. The agent calls this 5 minutes before expiry. On failure, retries every 30 seconds with exponential backoff. If the token expires before refresh, the RabbitMQ connection drops — the agent buffers locally (WAL) and reconnects when a new token is obtained.

## Credential State Machine

```
NO_CREDENTIAL → [slogr connect / bootstrap token] → HAS_CREDENTIAL → [slogr disconnect / deregister] → NO_CREDENTIAL
                                                         │
                                                    [token refresh]
                                                         │
                                                    HAS_CREDENTIAL (updated)
```

## What Never Touches Disk

- RabbitMQ JWT (in-memory only, refreshed hourly)
- Bootstrap token (read from env var, used once, discarded)
- Any password or API key after initial exchange
