---
status: locked
version: 3.0
---

# Profile Manager (Traffic Types)

## Overview

The desktop agent tests 3 user-selected traffic types simultaneously. Each type gets its own TWAMP session with a real traffic signature (DSCP, packet size, interval, count). Results show how the ISP treats each traffic class independently.

## 8 Traffic Types

| Name | Display | Packets | Interval | Size | DSCP | GREEN Thresholds |
|------|---------|---------|----------|------|------|-----------------|
| `internet` | General Internet | 50 | 50ms | 1500B | 0 (BE) | RTT<100, Jitter<30, Loss<1% |
| `gaming` | Gaming | 33 | 30ms | 120B | 34 (AF41) | RTT<50, Jitter<15, Loss<0.5% |
| `voip` | VoIP / Video Calls | 50 | 20ms | 200B | 46 (EF) | RTT<150, Jitter<20, Loss<1% |
| `streaming` | Streaming | 20 | 50ms | 1200B | 36 (AF42) | RTT<200, Jitter<50, Loss<2% |
| `cloud` | Cloud / SaaS | 30 | 50ms | 1500B | 32 (CS4) | RTT<100, Jitter<25, Loss<0.5% |
| `rdp` | Remote Desktop | 33 | 30ms | 500B | 26 (AF31) | RTT<80, Jitter<20, Loss<0.5% |
| `iot` | IoT / Telemetry | 10 | 100ms | 100B | 0 (BE) | RTT<500, Jitter<100, Loss<5% |
| `trading` | Financial Trading | 50 | 10ms | 64B | 46 (EF) | RTT<10, Jitter<2, Loss<0.01% |

## Selection Rules

- User selects exactly 3 types (default: Gaming, VoIP, Streaming)
- All 8 types selectable by all users (no freemium gating on types)
- Settings shows selected 3 by default, "Show all types (N more)" to expand
- Test interval dropdown and traceroute toggle below type selection

## DSCP Marking

`JavaUdpTransport.setTos()` calls `DatagramSocket.setTrafficClass()` to mark each packet with the correct DSCP value. Whether the OS/ISP honors it depends on the platform, but this shows the true result — if the ISP strips DSCP, the user sees that all traffic gets best-effort treatment.

## Measurement Flow

1. Scheduler starts cycle → all 3 cards go grey ("Testing...")
2. First type session runs (~3s) → that card turns green/yellow/red
3. Second type session runs (~3s) → that card updates
4. Third type session runs (~3s) → that card updates
5. Overall tray icon grade = worst of the 3

Each session uses `engine.measure()` with the type's `SlaProfile` (containing nPackets, intervalMs, packetSize, dscp).
