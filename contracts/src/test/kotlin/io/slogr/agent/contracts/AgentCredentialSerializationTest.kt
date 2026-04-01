package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AgentCredentialSerializationTest {

    private fun sampleCredential() = AgentCredential(
        agentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
        tenantId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
        displayName = "us-east-agent-01",
        jwt = "eyJhbGciOiJSUzI1NiJ9.test.signature",
        rabbitmqJwt = "eyJhbGciOiJSUzI1NiJ9.mq.signature",
        rabbitmqHost = "mq.slogr.io",
        rabbitmqPort = 5671,
        pubsubSubscription = "slogr.agent-commands.550e8400-e29b-41d4-a716-446655440001",
        issuedAt = Clock.System.now(),
        connectedVia = ConnectionMethod.API_KEY
    )

    @Test
    fun `AgentCredential round-trips through JSON`() {
        val original = sampleCredential()
        val json = SlogrJson.encodeToString(AgentCredential.serializer(), original)
        val decoded = SlogrJson.decodeFromString(AgentCredential.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON contains expected keys`() {
        val json = SlogrJson.encodeToString(AgentCredential.serializer(), sampleCredential())
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        for (key in listOf("agent_id", "tenant_id", "display_name", "jwt",
                            "rabbitmq_jwt", "rabbitmq_host", "rabbitmq_port",
                            "pubsub_subscription", "issued_at", "connected_via")) {
            assertTrue(obj.containsKey(key), "Missing key: $key")
        }
    }

    @Test
    fun `ConnectionMethod serializes correctly`() {
        val cred = sampleCredential()
        val json = SlogrJson.encodeToString(AgentCredential.serializer(), cred)
        assertTrue(json.contains("API_KEY"))
    }

    @Test
    fun `BOOTSTRAP_TOKEN connection method round-trips`() {
        val cred = sampleCredential().copy(connectedVia = ConnectionMethod.BOOTSTRAP_TOKEN)
        val json = SlogrJson.encodeToString(AgentCredential.serializer(), cred)
        val decoded = SlogrJson.decodeFromString(AgentCredential.serializer(), json)
        assertEquals(ConnectionMethod.BOOTSTRAP_TOKEN, decoded.connectedVia)
    }
}
