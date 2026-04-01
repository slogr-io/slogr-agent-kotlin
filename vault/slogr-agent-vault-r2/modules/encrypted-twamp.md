# Encrypted TWAMP Mode (mode=4)

**Status:** Locked
**New module in R2**

---

## Purpose

RFC 5357 defines three security modes: Unauthenticated (mode=1), Authenticated (mode=2), Encrypted (mode=4). R1 implements mode=1 only. R2 adds mode=4 for encrypted test packets.

## When It's Used

Encrypted mode is required when TWAMP packets traverse untrusted networks where packet contents could be inspected or tampered with. This is relevant for customer agents measuring across public internet paths where ISPs or intermediaries might modify or inspect UDP payloads.

## Implementation

### ServerGreeting Mode Negotiation

During the TWAMP control handshake, the Server (reflector side) advertises supported modes in the ServerGreeting. The Control-Client (sender side) selects the highest mutually-supported mode.

```
R1 ServerGreeting:  modes = 0x01  (unauthenticated only)
R2 ServerGreeting:  modes = 0x05  (unauthenticated + encrypted)
```

The Control-Client selects:
- mode=4 if both sides support it
- mode=1 if the reflector only supports unauthenticated
- Slogr ServerGreeting fingerprint (ADR-013) is preserved in both modes

### Key Derivation

Per RFC 5357 Section 3.1:
- Shared secret exchanged during control setup
- Session key derived from shared secret using Key Derivation Function (KDF)
- AES-CBC with 128-bit key for packet encryption
- HMAC-SHA1 for packet authentication (truncated to 128 bits)

### Packet Encryption (mode=4)

Encrypted fields in TWAMP test packets:
- Sequence Number
- Timestamp (T2 or T3 depending on direction)
- Error Estimate
- Receive Timestamp

Fields NOT encrypted:
- Packet Padding (may be truncated by reflector)

### Implementation Notes

- Use JCE (Java Cryptography Extension) for AES-CBC and HMAC-SHA1
- No JNI changes needed — encryption happens at Java level before/after JNI packet send/receive
- T2 is still captured at kernel level (JNI `recvmsg`). Encryption is applied after T2 capture.
- Performance impact: negligible for 100-packet sessions. AES-CBC on ~100 byte payloads is microseconds.

## Files

| File | Action |
|------|--------|
| `engine/twamp/TwampCrypto.kt` | NEW — AES-CBC encrypt/decrypt, HMAC-SHA1, KDF |
| `engine/twamp/TwampSessionSender.kt` | MODIFY — encrypt outbound packets when mode=4 |
| `engine/twamp/TwampSessionReflector.kt` | MODIFY — decrypt inbound, encrypt outbound when mode=4 |
| `engine/twamp/TwampControlHandler.kt` | MODIFY — negotiate mode=4 in ServerGreeting/SetupResponse |
| Tests | Known test vectors from RFC. Interop test with Cisco/Juniper reflector if available. |

## Fallback

If the remote reflector does not support mode=4, the agent falls back to mode=1 (unauthenticated) silently. No error, no warning — this is standard TWAMP negotiation behavior per the RFC.
