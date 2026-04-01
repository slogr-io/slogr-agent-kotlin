package io.slogr.agent.platform.commands

import io.slogr.agent.contracts.Schedule
import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import io.slogr.agent.platform.scheduler.ScheduleStore
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Handles `halt_measurement` commands (R2 kill switch).
 *
 * Immediately purges all in-memory measurement sessions and clears the persisted
 * schedule. The agent remains connected and continues sending health signals;
 * a subsequent `set_schedule` command resumes measurements.
 *
 * Payload fields (all optional):
 * - `timeout_seconds` — if clearing sessions takes longer than this, respond
 *   with `timed_out` instead of `acked` (default: 30).
 *
 * Tests: R2-HALT-01 through R2-HALT-04.
 */
class HaltMeasurementHandler(
    private val agentId:       UUID,
    private val tenantId:      UUID,
    private val scheduler:     TestScheduler,
    private val scheduleStore: ScheduleStore,
    /** Injectable clock for testing the timeout path. */
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CommandHandler {

    private val log = LoggerFactory.getLogger(HaltMeasurementHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val timeoutMs = (payload.jsonObject["timeout_seconds"]?.jsonPrimitive?.int ?: 30) * 1000L

        val t0 = nowMs()

        val empty = Schedule(sessions = emptyList(), receivedAt = Clock.System.now(), commandId = commandId)
        scheduler.updateSchedule(empty)
        scheduleStore.clear()

        val elapsed = nowMs() - t0

        return if (elapsed > timeoutMs) {
            log.warn("halt_measurement: timed out after ${elapsed}ms (limit ${timeoutMs}ms)")
            CommandResponse(commandId, agentId, tenantId, "timed_out")
        } else {
            log.info("halt_measurement: all sessions purged in ${elapsed}ms")
            CommandResponse(commandId, agentId, tenantId, "acked")
        }
    }
}
