package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID

class ScheduleSerializationTest {

    private val voipProfile = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 2000L,
        dscp = 46, packetSize = 172,
        rttGreenMs = 150f, rttRedMs = 400f,
        jitterGreenMs = 30f, jitterRedMs = 100f,
        lossGreenPct = 1f, lossRedPct = 5f
    )

    private fun sampleSession() = SessionConfig(
        pathId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
        targetIp = InetAddress.getByName("10.0.0.1"),
        profile = voipProfile
    )

    private fun sampleSchedule() = Schedule(
        sessions = listOf(sampleSession()),
        receivedAt = Clock.System.now()
    )

    @Test
    fun `SessionConfig round-trips through JSON`() {
        val original = sampleSession()
        val json = SlogrJson.encodeToString(SessionConfig.serializer(), original)
        val decoded = SlogrJson.decodeFromString(SessionConfig.serializer(), json)
        assertEquals(original.pathId, decoded.pathId)
        assertEquals(original.targetIp.hostAddress, decoded.targetIp.hostAddress)
        assertEquals(original.targetPort, decoded.targetPort)
        assertEquals(original.profile, decoded.profile)
    }

    @Test
    fun `SessionConfig targetPort defaults to 862`() {
        assertEquals(862, sampleSession().targetPort)
    }

    @Test
    fun `SessionConfig intervalSeconds defaults to 300`() {
        assertEquals(300, sampleSession().intervalSeconds)
    }

    @Test
    fun `SessionConfig tracerouteEnabled defaults to true`() {
        assertTrue(sampleSession().tracerouteEnabled)
    }

    @Test
    fun `SessionConfig skipCycles defaults to 0`() {
        assertEquals(0, sampleSession().skipCycles)
    }

    @Test
    fun `SessionConfig JSON contains target_port key`() {
        val json = SlogrJson.encodeToString(SessionConfig.serializer(), sampleSession())
        val obj = SlogrJson.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("target_port"), "Missing key: target_port")
        assertTrue(obj.containsKey("target_ip"), "Missing key: target_ip")
        assertTrue(obj.containsKey("path_id"), "Missing key: path_id")
    }

    @Test
    fun `Schedule round-trips through JSON`() {
        val original = sampleSchedule()
        val json = SlogrJson.encodeToString(Schedule.serializer(), original)
        val decoded = SlogrJson.decodeFromString(Schedule.serializer(), json)
        assertEquals(original.sessions.size, decoded.sessions.size)
        assertNull(decoded.commandId)
    }

    @Test
    fun `Schedule with commandId round-trips`() {
        val cmdId = UUID.randomUUID()
        val schedule = sampleSchedule().copy(commandId = cmdId)
        val json = SlogrJson.encodeToString(Schedule.serializer(), schedule)
        val decoded = SlogrJson.decodeFromString(Schedule.serializer(), json)
        assertEquals(cmdId, decoded.commandId)
    }

    @Test
    fun `TwampTarget round-trips through JSON`() {
        val target = TwampTarget(ip = InetAddress.getByName("192.168.1.1"))
        val json = SlogrJson.encodeToString(TwampTarget.serializer(), target)
        val decoded = SlogrJson.decodeFromString(TwampTarget.serializer(), json)
        assertEquals(target.ip.hostAddress, decoded.ip.hostAddress)
        assertEquals(862, decoded.port)
        assertEquals(TwampAuthMode.UNAUTHENTICATED, decoded.authMode)
    }
}
