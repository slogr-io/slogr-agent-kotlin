package io.slogr.agent.platform.registration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class KeyValidationCacheTest {

    @TempDir
    lateinit var tempDir: Path

    private fun cache() = KeyValidationCache(tempDir.resolve("key_validation.json"))

    @Test
    fun `read returns null when file does not exist`() {
        assertNull(cache().read())
    }

    @Test
    fun `write and read round-trips a valid entry`() {
        val c = cache()
        val entry = KeyValidationCache.Entry(
            valid       = true,
            keyType     = "free",
            tenantId    = "aaaaaaaa-0000-0000-0000-000000000000",
            validatedAt = Instant.now().toString()
        )
        c.write(entry)
        val read = c.read()
        assertNotNull(read)
        assertTrue(read!!.valid)
        assertEquals("free", read.keyType)
        assertEquals("aaaaaaaa-0000-0000-0000-000000000000", read.tenantId)
    }

    @Test
    fun `read returns null for expired entry`() {
        val c = cache()
        val expired = KeyValidationCache.Entry(
            valid       = true,
            keyType     = "free",
            tenantId    = null,
            validatedAt = Instant.now().minusSeconds(25 * 3600).toString()
        )
        c.write(expired)
        assertNull(c.read())
    }

    @Test
    fun `read returns null for corrupt file`() {
        val file = tempDir.resolve("key_validation.json")
        file.toFile().writeText("not valid json {{{")
        assertNull(cache().read())
    }

    @Test
    fun `write stores invalid entry`() {
        val c = cache()
        c.write(KeyValidationCache.Entry(valid = false, validatedAt = Instant.now().toString()))
        val read = c.read()
        assertNotNull(read)
        assertFalse(read!!.valid)
        assertNull(read.tenantId)
    }
}
