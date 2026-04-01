---
status: locked
version: 1.0
depends-on:
  - architecture/module-map
---

# Extensibility

## Kotlin Multiplatform Migration Path

The `contracts/` module is designed to be the future KMP common module. Rules:
- No JVM-specific imports (no `java.io`, no `java.sql`)
- Use `kotlin.time` instead of `java.time` where possible
- Use `kotlinx.datetime.Instant` instead of `java.time.Instant`
- Use `kotlinx.serialization` instead of Jackson/Gson
- UUID can use `kotlin-uuid` multiplatform library

When KMP is activated, `contracts/` compiles to JVM, Native, and JS. `engine/` stays JVM-only (JNI dependency). Platform-specific implementations of `MeasurementEngine` are created for each target.

## Adding New Measurement Types

The `MeasurementEngine` interface is the extension point. To add a new measurement type (e.g., HTTP probe, DNS resolution timing):

1. Add a new data class to `contracts/` (e.g., `HttpProbeResult`)
2. Add a new method to `MeasurementEngine` (e.g., `suspend fun httpProbe(...)`)
3. Implement in `engine/`
4. Update `ResultPublisher` if the new type needs its own RabbitMQ routing key

No changes to `platform/` modules — they work with the interfaces.

## Adding New Output Destinations

Implement `ResultPublisher`. Current implementations: RabbitMQ, OTLP/HTTP, stdout. Future: MQTT, Kafka, webhook. The publisher interface doesn't know or care what it's publishing to.

## Adding New CLI Commands

Add to the `Command` enum in `cli/`. Add a handler function in `app/Main.kt`. The CLI parser is a flat dispatcher — no command hierarchy to refactor.

## Plugin System (Future, Not R1)

If third-party extensibility is needed, define a `SlogrPlugin` interface that registers new measurement types, output destinations, or CLI commands. Load plugins from a `plugins/` directory via `ServiceLoader`. Not needed for R1 — the module structure already supports internal extensibility without dynamic loading.
