package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class MeasurementBundleTest {

    private val voipProfile = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 2000L,
        dscp = 46, packetSize = 172,
        rttGreenMs = 150f, rttRedMs = 400f,
        jitterGreenMs = 30f, jitterRedMs = 100f,
        lossGreenPct = 1f, lossRedPct = 5f
    )

    private fun sampleTwampResult() = MeasurementResult(
        sessionId = UUID.randomUUID(),
        pathId = UUID.randomUUID(),
        sourceAgentId = UUID.randomUUID(),
        destAgentId = UUID.randomUUID(),
        srcCloud = "aws", srcRegion = "us-east-1",
        dstCloud = "gcp", dstRegion = "us-central1",
        windowTs = Clock.System.now(),
        profile = voipProfile,
        fwdMinRttMs = 10f, fwdAvgRttMs = 12f, fwdMaxRttMs = 15f,
        fwdJitterMs = 1.2f, fwdLossPct = 0f,
        packetsSent = 100, packetsRecv = 100
    )

    @Test
    fun `MeasurementBundle with only twamp result round-trips`() {
        val bundle = MeasurementBundle(
            twamp = sampleTwampResult(),
            grade = SlaGrade.GREEN
        )
        val json = SlogrJson.encodeToString(MeasurementBundle.serializer(), bundle)
        val decoded = SlogrJson.decodeFromString(MeasurementBundle.serializer(), json)
        assertEquals(bundle.grade, decoded.grade)
        assertNull(decoded.traceroute)
        assertNull(decoded.pathChange)
    }

    @Test
    fun `MeasurementBundle grade defaults through correctly`() {
        for (grade in SlaGrade.entries) {
            val bundle = MeasurementBundle(twamp = sampleTwampResult(), grade = grade)
            val json = SlogrJson.encodeToString(MeasurementBundle.serializer(), bundle)
            val decoded = SlogrJson.decodeFromString(MeasurementBundle.serializer(), json)
            assertEquals(grade, decoded.grade)
        }
    }
}
