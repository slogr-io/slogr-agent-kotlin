---
status: locked
version: 1.0
depends-on:
  - architecture/module-map
---

# Testing Strategy

## Test Pyramid

| Level | Coverage Target | Runner | What |
|-------|----------------|--------|------|
| Unit | > 80% of engine/ and contracts/ | JUnit 5 + Kotlin | Pure logic: packet parsing, SLA eval, ASN lookup, path change, dedup hash |
| Integration | All module boundaries | JUnit 5 + Testcontainers | RabbitMQ publish/consume, Pub/Sub mock, WAL read/write, credential store encrypt/decrypt |
| Contract | All backend interfaces | JUnit 5 | Verify agent output matches ClickHouse schema (twamp_raw, traceroute_raw, agent_health field names and types) |
| Load | Concurrency limits | JUnit 5 + custom harness | 20 concurrent TWAMP sessions, 300-session schedule, memory under 384 MB |
| Security | All untrusted inputs | JUnit 5 + custom fuzzer | CLI arg injection, malformed Pub/Sub commands, oversized packets, invalid JWT |
| End-to-end | Golden path | Docker Compose | Agent registers → receives schedule → runs test → publishes to RabbitMQ → verifiable in test consumer |

## Unit Tests — engine/

| Module | Test Cases |
|--------|-----------|
| TWAMP packet parsing | ServerGreeting encode/decode, SetUpResponse encode/decode, RequestTWSession, all RFC 5357 packet types. Test against known byte sequences from the Java agent's test data. |
| TWAMP timing | Fixed interval produces packets at expected rate. Poisson produces exponential distribution. |
| Traceroute | Mock JNI results → verify hop assembly, timeout handling, mode fallback logic |
| ASN resolver | Load test MMDB → verify known IP→ASN mappings. Graceful degradation when DB missing. |
| Path change | Same path → no change. Different path → change with correct diff. First run → no change (unknown baseline). |
| SLA evaluator | GREEN for all-good metrics. YELLOW for one metric between thresholds. RED for one metric above red. |
| Dedup hash | Same MeasurementResult → same hash. Changed field → different hash. |

## Unit Tests — platform/

| Module | Test Cases |
|--------|-----------|
| CLI parser | All commands parse correctly. Invalid flags produce helpful errors. Missing required args → exit code 2. |
| Scheduler | Schedule with 3 sessions → all three fire at correct intervals. Schedule update replaces previous. |
| WAL | Write 100 entries → replay all 100. Mark 50 as published → replay returns 50. Compact → file shrinks. Size limit → oldest evicted. |
| Credential store | Encrypt → decrypt round-trip. Corrupted file → clean error. Missing file → returns null. |
| OTLP exporter | MeasurementResult → correct OTLP metric names and attributes. Batch fills → export triggered. |

## Integration Tests

Require Docker (Testcontainers):

| Test | Setup | Verify |
|------|-------|--------|
| RabbitMQ publish | Testcontainers RabbitMQ | Publish MeasurementResult → consume from queue → verify JSON matches schema |
| RabbitMQ JWT auth | RabbitMQ with JWT plugin | Agent connects with JWT → publish succeeds. Expired JWT → connection refused. |
| Pub/Sub commands | Mock Pub/Sub (or emulator) | Send set_schedule command → verify agent updates schedule. Send run_test → verify response published. |
| Registration flow | Mock HTTP server | Agent POSTs with bootstrap token → receives credential → stores to disk. |
| Upgrade flow | Mock HTTP server + temp binary | Agent downloads → verifies checksum → swaps binary path. Bad checksum → rejected. |

## Contract Tests

Verify the agent's output format matches what the backend expects. These tests compare the agent's serialized output against the ClickHouse schema field-by-field:

```kotlin
@Test
fun `MeasurementResult JSON matches twamp_raw schema`() {
    val result = createTestMeasurementResult()
    val json = result.toJson()
    // Verify all required ClickHouse columns are present
    assertContainsField(json, "tenant_id", UUID::class)
    assertContainsField(json, "session_id", UUID::class)
    assertContainsField(json, "source_agent_id", UUID::class)
    assertContainsField(json, "path_id", UUID::class)
    assertContainsField(json, "window_ts", Instant::class)
    assertContainsField(json, "fwd_avg_rtt_ms", Float::class)
    // ... all fields from clickhouse-schema.md
}
```

## Load Tests

Run on a t3.micro or equivalent resource-constrained environment:

| Test | Pass Criteria |
|------|--------------|
| 20 concurrent TWAMP sessions (1000 packets each) | All complete, no OOM, RSS < 512 MB |
| 300-session schedule, 5-min interval | All sessions run within window, no backlog after 1 hour |
| RabbitMQ disconnect + reconnect after 5 minutes | WAL drains successfully, no data loss |
| 10,000 traceroute hop lookups (MaxMind) | Complete in < 100ms total |

## Security Tests

| Test | What |
|------|------|
| CLI injection | `slogr-agent check "127.0.0.1; rm -rf /"` → must not execute shell command |
| Malformed Pub/Sub command | Send JSON with missing fields, wrong types, extra fields → agent responds with error, does not crash |
| Oversized TWAMP packet | Send 64KB packet to reflector → reject without buffer overflow |
| Invalid upgrade URL | `download_url: "http://evil.com/malware"` → rejected (not releases.slogr.io) |
| Expired JWT | Connect with expired RabbitMQ JWT → connection refused, agent falls back to WAL |
| Responder flood | 1000 session requests in 1 second → rate limited, agent stays responsive |

## CI Pipeline (GitHub Actions)

```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'corretto' }
      - name: Build native library
        run: make -C native/
      - name: Build and test
        run: ./gradlew build test
      - name: Integration tests
        run: ./gradlew integrationTest
      - name: Security tests
        run: ./gradlew securityTest

  build-native:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest]
        arch: [amd64, arm64]
    runs-on: ${{ matrix.os }}
    steps:
      - name: Compile libslogr-native
        run: make -C native/ ARCH=${{ matrix.arch }}
      - uses: actions/upload-artifact@v4
        with: { name: "native-${{ matrix.os }}-${{ matrix.arch }}", path: "native/build/" }

  package:
    needs: [test, build-native]
    steps:
      - name: Build fat JAR
        run: ./gradlew shadowJar
      - name: Build Docker image
        run: docker buildx build --platform linux/amd64,linux/arm64 -t slogr/agent .
      - name: Build RPM
        run: ./gradlew buildRpm
      - name: Build DEB
        run: ./gradlew buildDeb
```
