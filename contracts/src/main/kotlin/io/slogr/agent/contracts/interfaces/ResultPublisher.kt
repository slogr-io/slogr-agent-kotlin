package io.slogr.agent.contracts.interfaces

import io.slogr.agent.contracts.HealthSnapshot
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.TracerouteResult

interface ResultPublisher {
    /** Publish a TWAMP measurement result. Returns true if acknowledged. */
    suspend fun publishMeasurement(result: MeasurementResult): Boolean

    /** Publish a traceroute snapshot. Returns true if acknowledged. */
    suspend fun publishTraceroute(result: TracerouteResult): Boolean

    /** Publish a health snapshot. Returns true if acknowledged. */
    suspend fun publishHealth(snapshot: HealthSnapshot): Boolean

    /** Flush any buffered data. Called on shutdown. */
    suspend fun flush()
}
