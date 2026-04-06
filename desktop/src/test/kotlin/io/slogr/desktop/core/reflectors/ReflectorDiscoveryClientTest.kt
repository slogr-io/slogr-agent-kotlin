package io.slogr.desktop.core.reflectors

import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ReflectorDiscoveryClientTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var cache: ReflectorCache
    private lateinit var stateManager: DesktopStateManager
    private lateinit var client: ReflectorDiscoveryClient

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-discovery-test")
        cache = ReflectorCache(tempDir)
        stateManager = DesktopStateManager(EncryptedKeyStore(tempDir))
        stateManager.initialize()
        client = ReflectorDiscoveryClient(cache, stateManager)
        // Ensure mock mode
        ReflectorDiscoveryClient.USE_REAL_API = false
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `discover loads mock reflectors`() = runBlocking {
        client.discover()
        assertTrue(client.reflectors.value.isNotEmpty())
        assertNull(client.error.value)
        assertFalse(client.isLoading.value)
    }

    @Test
    fun `discover sets user region from mock`() = runBlocking {
        client.discover()
        assertEquals("pk-sindh", client.userRegion.value)
    }

    @Test
    fun `discover caches results`() = runBlocking {
        client.discover()
        assertNotNull(cache.load())
        assertFalse(cache.isExpired())
    }

    @Test
    fun `discover uses cache when not expired`() = runBlocking {
        // First discovery populates cache
        client.discover()
        val firstCount = client.reflectors.value.size

        // Second discovery should use cache (no error even in mock mode)
        val client2 = ReflectorDiscoveryClient(cache, stateManager)
        client2.discover()
        assertEquals(firstCount, client2.reflectors.value.size)
    }

    @Test
    fun `filterByTier returns only free for ANONYMOUS users`() = runBlocking {
        client.discover()
        val filtered = client.filterByTier(client.reflectors.value)
        assertTrue(filtered.all { it.tier == "free" })
        assertTrue(filtered.isNotEmpty())
    }

    @Test
    fun `filterByTier returns all for CONNECTED users`() = runBlocking {
        stateManager.setApiKey("sk_live_test")
        client.discover()
        val all = client.reflectors.value
        val filtered = client.filterByTier(all)
        assertEquals(all.size, filtered.size)
    }

    @Test
    fun `mock reflectors include both free and paid tiers`() = runBlocking {
        client.discover()
        val tiers = client.reflectors.value.map { it.tier }.toSet()
        assertTrue("free" in tiers)
        assertTrue("paid" in tiers)
    }

    @Test
    fun `refresh updates reflectors`() = runBlocking {
        client.refresh()
        assertTrue(client.reflectors.value.isNotEmpty())
        assertNull(client.error.value)
    }
}
