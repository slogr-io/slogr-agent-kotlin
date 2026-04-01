package io.slogr.agent.platform.credential

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class CredentialStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val store by lazy { EncryptedCredentialStore(tempDir.absolutePath) }

    // ── Load when file absent ─────────────────────────────────────────────────

    @Test
    fun `load returns null when no credential file exists`() {
        assertNull(store.load())
        assertFalse(store.isConnected())
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `store and load round-trip preserves all credential fields`() {
        val cred = makeCredential()
        store.store(cred)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(cred.agentId, loaded!!.agentId)
        assertEquals(cred.tenantId, loaded.tenantId)
        assertEquals(cred.displayName, loaded.displayName)
        assertEquals(cred.jwt, loaded.jwt)
        assertEquals(cred.rabbitmqJwt, loaded.rabbitmqJwt)
        assertEquals(cred.rabbitmqHost, loaded.rabbitmqHost)
        assertEquals(cred.rabbitmqPort, loaded.rabbitmqPort)
        assertEquals(cred.connectedVia, loaded.connectedVia)
        assertTrue(store.isConnected())
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the credential`() {
        store.store(makeCredential())
        assertNotNull(store.load())

        store.delete()
        assertNull(store.load())
        assertFalse(store.isConnected())
    }

    // ── Corrupt file → null ───────────────────────────────────────────────────

    @Test
    fun `corrupt credential file returns null without throwing`() {
        File(tempDir, "credential.enc").writeBytes(ByteArray(20) { it.toByte() })
        assertNull(store.load())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCredential() = AgentCredential(
        agentId             = UUID.randomUUID(),
        tenantId            = UUID.randomUUID(),
        displayName         = "test-agent",
        jwt                 = "eyJhbGciOiJSUzI1NiJ9.test.sig",
        rabbitmqJwt         = "eyJhbGciOiJSUzI1NiJ9.mq.sig",
        rabbitmqHost        = "mq.slogr.io",
        rabbitmqPort        = 5671,
        pubsubSubscription  = "projects/slogr/subscriptions/agent-test",
        issuedAt            = Clock.System.now(),
        connectedVia        = ConnectionMethod.API_KEY
    )
}
