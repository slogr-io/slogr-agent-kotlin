package io.slogr.desktop.core.reflectors

import java.nio.file.Files
import kotlin.test.*

class ReflectorCacheTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var cache: ReflectorCache

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-cache-test")
        cache = ReflectorCache(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private val sampleResponse = ReflectorDiscoveryResponse(
        reflectors = listOf(
            Reflector("1", "us-east", "aws", "1.1.1.1", 862, 39.04, -77.49, "free"),
            Reflector("2", "eu-west", "aws", "2.2.2.2", 862, 53.35, -6.26, "free"),
        ),
        yourRegion = "pk-sindh",
        yourIp = "203.0.113.45",
    )

    @Test
    fun `load returns null when no cache file exists`() {
        assertNull(cache.load())
    }

    @Test
    fun `isExpired returns true when no cache exists`() {
        assertTrue(cache.isExpired())
    }

    @Test
    fun `save and load roundtrips reflector data`() {
        cache.save(sampleResponse)
        val loaded = cache.load()
        assertNotNull(loaded)
        assertEquals(2, loaded.reflectors.size)
        assertEquals("us-east", loaded.reflectors[0].region)
        assertEquals("pk-sindh", loaded.yourRegion)
        assertEquals("203.0.113.45", loaded.yourIp)
    }

    @Test
    fun `fresh cache is not expired`() {
        cache.save(sampleResponse)
        assertFalse(cache.isExpired())
    }

    @Test
    fun `clear removes cache file`() {
        cache.save(sampleResponse)
        assertNotNull(cache.load())
        cache.clear()
        assertNull(cache.load())
    }

    @Test
    fun `corrupted cache file returns null`() {
        tempDir.resolve("reflectors.json").toFile().writeText("not valid json")
        assertNull(cache.load())
    }

    @Test
    fun `reflector displayName formats region correctly`() {
        val r = Reflector("1", "us-east-1", "aws", "h", 862, 0.0, 0.0, "free")
        assertEquals("Us East", r.displayName)
    }

    @Test
    fun `reflector displayName handles simple regions`() {
        val r = Reflector("1", "eu-west", "aws", "h", 862, 0.0, 0.0, "free")
        assertEquals("Eu West", r.displayName)
    }
}
