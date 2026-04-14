package io.slogr.desktop.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoUpdaterTest {

    private val updater = AutoUpdater()

    @Test
    fun `isNewer returns true for higher major version`() {
        assertTrue(updater.isNewer("2.0.0", "1.1.0"))
    }

    @Test
    fun `isNewer returns true for higher minor version`() {
        assertTrue(updater.isNewer("1.2.0", "1.1.0"))
    }

    @Test
    fun `isNewer returns true for higher patch version`() {
        assertTrue(updater.isNewer("1.1.1", "1.1.0"))
    }

    @Test
    fun `isNewer returns false for same version`() {
        assertFalse(updater.isNewer("1.1.0", "1.1.0"))
    }

    @Test
    fun `isNewer returns false for lower version`() {
        assertFalse(updater.isNewer("1.0.9", "1.1.0"))
    }

    @Test
    fun `isNewer handles different length versions`() {
        assertTrue(updater.isNewer("1.1.0.1", "1.1.0"))
        assertFalse(updater.isNewer("1.1", "1.1.0"))
    }

    @Test
    fun `UpdateInfo platformDownloadUrl prefers platform-specific URL`() {
        val info = AutoUpdater.UpdateInfo(
            version = "1.2.0",
            downloadUrlWindows = "https://example.com/win.msi",
            downloadUrlMacos = "https://example.com/mac.dmg",
            downloadUrl = "https://example.com/fallback",
        )
        // On Windows CI, should return Windows URL
        val url = info.platformDownloadUrl
        assertNotNull(url)
        // Should be one of the platform URLs, not null
        assertTrue(url == "https://example.com/win.msi" || url == "https://example.com/mac.dmg")
    }

    @Test
    fun `UpdateInfo platformDownloadUrl falls back to generic URL`() {
        val info = AutoUpdater.UpdateInfo(
            version = "1.2.0",
            downloadUrl = "https://example.com/fallback",
        )
        assertEquals("https://example.com/fallback", info.platformDownloadUrl)
    }

    @Test
    fun `UpdateInfo required defaults to false`() {
        val info = AutoUpdater.UpdateInfo(version = "1.2.0")
        assertFalse(info.required)
    }

    @Test
    fun `UpdateInfo JSON deserialization with new fields`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val input = """{"version":"2.0.0","download_url_windows":"https://a.com/w.msi","download_url_macos":"https://a.com/m.dmg","release_notes":"Bug fixes","required":true}"""
        val info = json.decodeFromString<AutoUpdater.UpdateInfo>(input)
        assertEquals("2.0.0", info.version)
        assertEquals("https://a.com/w.msi", info.downloadUrlWindows)
        assertEquals("https://a.com/m.dmg", info.downloadUrlMacos)
        assertEquals("Bug fixes", info.releaseNotes)
        assertTrue(info.required)
    }

    @Test
    fun `UpdateInfo JSON deserialization with legacy format`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val input = """{"version":"1.2.0","download_url":"https://slogr.io/Slogr-1.2.0.msi"}"""
        val info = json.decodeFromString<AutoUpdater.UpdateInfo>(input)
        assertEquals("1.2.0", info.version)
        assertEquals("https://slogr.io/Slogr-1.2.0.msi", info.downloadUrl)
        assertNull(info.downloadUrlWindows)
        assertNull(info.releaseNotes)
        assertFalse(info.required)
    }

    @Test
    fun `dismiss sets updateAvailable to null`() {
        updater.dismiss()
        assertNull(updater.updateAvailable.value)
    }
}
