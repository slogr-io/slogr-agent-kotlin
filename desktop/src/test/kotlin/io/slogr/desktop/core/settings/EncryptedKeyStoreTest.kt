package io.slogr.desktop.core.settings

import java.nio.file.Files
import kotlin.test.*

class EncryptedKeyStoreTest {

    private lateinit var keyStore: EncryptedKeyStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-key-test")
        keyStore = EncryptedKeyStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `load returns null when no key stored`() {
        assertNull(keyStore.load())
    }

    @Test
    fun `store and load roundtrips free key`() {
        keyStore.store("sk_free_abc123")
        assertEquals("sk_free_abc123", keyStore.load())
    }

    @Test
    fun `store and load roundtrips live key`() {
        keyStore.store("sk_live_xyz789")
        assertEquals("sk_live_xyz789", keyStore.load())
    }

    @Test
    fun `delete removes stored key`() {
        keyStore.store("sk_live_test")
        assertNotNull(keyStore.load())
        keyStore.delete()
        assertNull(keyStore.load())
    }

    @Test
    fun `stored key is encrypted on disk`() {
        val key = "sk_live_should_be_encrypted"
        keyStore.store(key)
        val raw = tempDir.resolve(".keystore").toFile().readText()
        assertFalse(raw.contains(key), "Key should not appear as plaintext on disk")
    }

    @Test
    fun `overwriting key replaces previous`() {
        keyStore.store("sk_free_old")
        keyStore.store("sk_live_new")
        assertEquals("sk_live_new", keyStore.load())
    }

    @Test
    fun `corrupted file returns null`() {
        tempDir.resolve(".keystore").toFile().writeText("garbage")
        assertNull(keyStore.load())
    }
}
