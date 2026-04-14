package io.slogr.desktop.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IspDetectorTest {

    private val detector = IspDetector()

    @Test
    fun `parseOrgLine extracts ASN and ISP name`() {
        val result = detector.parseOrgLine("AS17557 Pakistan Telecommunication Company Limited", "1.2.3.4")
        assertNotNull(result)
        assertEquals(17557, result.asn)
        assertEquals("Pakistan Telecommunication Company Limited", result.ispName)
        assertEquals("1.2.3.4", result.publicIp)
    }

    @Test
    fun `parseOrgLine handles single-word ISP name`() {
        val result = detector.parseOrgLine("AS15169 Google", "8.8.8.8")
        assertNotNull(result)
        assertEquals(15169, result.asn)
        assertEquals("Google", result.ispName)
    }

    @Test
    fun `parseOrgLine returns null for blank input`() {
        assertNull(detector.parseOrgLine("", "1.2.3.4"))
        assertNull(detector.parseOrgLine("   ", "1.2.3.4"))
    }

    @Test
    fun `parseOrgLine returns null for missing AS prefix`() {
        assertNull(detector.parseOrgLine("17557 Some ISP", "1.2.3.4"))
    }

    @Test
    fun `parseOrgLine returns null for non-numeric ASN`() {
        assertNull(detector.parseOrgLine("ASxyz Some ISP", "1.2.3.4"))
    }

    @Test
    fun `parseOrgLine returns null for ASN without name`() {
        assertNull(detector.parseOrgLine("AS17557", "1.2.3.4"))
    }

    @Test
    fun `parseOrgLine returns null for ASN with blank name`() {
        assertNull(detector.parseOrgLine("AS17557 ", "1.2.3.4"))
    }

    @Test
    fun `IspInfo displayText format`() {
        val info = IspInfo(ispName = "Comcast", asn = 7922, publicIp = "73.1.2.3")
        assertEquals("Comcast (AS7922)", info.displayText)
    }

    @Test
    fun `IspInfo serialization round-trip`() {
        val info = IspInfo(ispName = "AT&T", asn = 7018, publicIp = "12.0.0.1")
        val json = kotlinx.serialization.json.Json.encodeToString(IspInfo.serializer(), info)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(IspInfo.serializer(), json)
        assertEquals(info, decoded)
    }
}
