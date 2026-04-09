package io.slogr.agent.engine.asn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

class Ip2AsnResolverTest {

    // ── Synthetic ranges for unit tests ─────────────────────────────────────

    private val testRanges = arrayOf(
        IpRange(start = ipStringToLong("1.0.0.0"),   end = ipStringToLong("1.0.0.255"),   asn = 13335, name = "CLOUDFLARENET"),
        IpRange(start = ipStringToLong("8.8.4.0"),   end = ipStringToLong("8.8.4.255"),   asn = 15169, name = "GOOGLE"),
        IpRange(start = ipStringToLong("104.16.0.0"), end = ipStringToLong("104.16.255.255"), asn = 13335, name = "CLOUDFLARENET")
    )

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `resolve returns AsnInfo for IP in middle range`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        val result = resolver.resolve(InetAddress.getByName("8.8.4.8"))
        assertNotNull(result)
        assertEquals(15169, result!!.asn)
        assertEquals("GOOGLE", result.name)
    }

    @Test
    fun `isAvailable returns true when ranges loaded`() {
        assertTrue(Ip2AsnResolver(testRanges).isAvailable())
    }

    // ── Boundary ────────────────────────────────────────────────────────────

    @Test
    fun `resolve matches IP at exact range start`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        val result = resolver.resolve(InetAddress.getByName("1.0.0.0"))
        assertNotNull(result)
        assertEquals(13335, result!!.asn)
    }

    @Test
    fun `resolve matches IP at exact range end`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        val result = resolver.resolve(InetAddress.getByName("1.0.0.255"))
        assertNotNull(result)
        assertEquals(13335, result!!.asn)
    }

    // ── Gap between ranges ──────────────────────────────────────────────────

    @Test
    fun `resolve returns null for IP in gap between ranges`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        // 2.0.0.1 is between range 1 (1.0.0.0-1.0.0.255) and range 2 (8.8.4.0-8.8.4.255)
        assertNull(resolver.resolve(InetAddress.getByName("2.0.0.1")))
    }

    @Test
    fun `resolve returns null for IP before first range`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        assertNull(resolver.resolve(InetAddress.getByName("0.0.0.1")))
    }

    @Test
    fun `resolve returns null for IP after last range`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        assertNull(resolver.resolve(InetAddress.getByName("255.255.255.255")))
    }

    // ── IPv6 ────────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns null for IPv6 address`() = runTest {
        val resolver = Ip2AsnResolver(testRanges)
        val ipv6 = InetAddress.getByName("2001:4860:4860::8888")
        assertTrue(ipv6 is Inet6Address)
        assertNull(resolver.resolve(ipv6))
    }

    // ── Empty dataset ───────────────────────────────────────────────────────

    @Test
    fun `empty dataset - isAvailable returns false`() {
        assertFalse(Ip2AsnResolver(emptyArray()).isAvailable())
    }

    @Test
    fun `empty dataset - resolve returns null`() = runTest {
        assertNull(Ip2AsnResolver(emptyArray()).resolve(InetAddress.getByName("8.8.8.8")))
    }

    // ── Parse ───────────────────────────────────────────────────────────────

    @Test
    fun `parse valid 5-line TSV`() {
        val tsv = listOf(
            "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
            "1.0.4.0\t1.0.7.255\t38803\tAU\tWPL-AS-AP",
            "1.0.64.0\t1.0.127.255\t18144\tJP\tAS-ENECOM",
            "8.8.4.0\t8.8.4.255\t15169\tUS\tGOOGLE",
            "8.8.8.0\t8.8.8.255\t15169\tUS\tGOOGLE"
        )
        val ranges = Ip2AsnResolver.parse(tsv.asSequence())
        assertEquals(5, ranges.size)
        assertEquals(13335, ranges[0].asn)
        assertEquals("GOOGLE", ranges[4].name)
        // Verify sorted by start
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].start >= ranges[i - 1].start)
        }
    }

    @Test
    fun `parse skips ASN 0 lines`() {
        val tsv = listOf(
            "0.0.0.0\t0.255.255.255\t0\tNone\tNot routed",
            "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
            "10.0.0.0\t10.255.255.255\t0\tNone\tPrivate range"
        )
        val ranges = Ip2AsnResolver.parse(tsv.asSequence())
        assertEquals(1, ranges.size)
        assertEquals(13335, ranges[0].asn)
    }

    @Test
    fun `parse skips corrupt and malformed lines`() {
        val tsv = listOf(
            "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",   // valid
            "not an ip\t1.0.0.255\t13335\tUS\tBAD",             // malformed start IP
            "1.0.0.0\t1.0.0.255",                                // too few columns
            "",                                                    // blank line
            "# comment line",                                      // comment
            "8.8.8.0\t8.8.8.255\tNaN\tUS\tBAD_ASN",             // non-numeric ASN
            "8.8.4.0\t8.8.4.255\t15169\tUS\tGOOGLE"             // valid
        )
        val ranges = Ip2AsnResolver.parse(tsv.asSequence())
        assertEquals(2, ranges.size)
        assertEquals(13335, ranges[0].asn)
        assertEquals(15169, ranges[1].asn)
    }

    // ── fromFile ────────────────────────────────────────────────────────────

    @Test
    fun `fromFile returns null for missing file`() {
        assertNull(Ip2AsnResolver.fromFile(File("/nonexistent/ip2asn-v4.tsv")))
    }

    @Test
    fun `fromFile loads valid temp file`(@TempDir tempDir: File) {
        val tsvFile = File(tempDir, "ip2asn-v4.tsv")
        tsvFile.writeText(
            "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET\n" +
            "8.8.8.0\t8.8.8.255\t15169\tUS\tGOOGLE\n"
        )
        val resolver = Ip2AsnResolver.fromFile(tsvFile)
        assertNotNull(resolver)
        assertTrue(resolver!!.isAvailable())
    }

    // ── fromResource — bundled database tests ─────────────────────────────

    private fun loadBundled(): Ip2AsnResolver {
        val resolver = Ip2AsnResolver.fromResource()
        assertNotNull(resolver, "Bundled ip2asn-v4.tsv.gz missing from classpath")
        return resolver!!
    }

    @Test
    fun `fromResource loads successfully and returns non-null`() {
        assertNotNull(Ip2AsnResolver.fromResource())
    }

    @Test
    fun `fromResource has more than 400k entries`() {
        val resolver = loadBundled()
        assertTrue(resolver.size > 400_000,
            "Expected > 400,000 ranges, got ${resolver.size}")
    }

    @Test
    fun `fromResource isAvailable returns true`() {
        assertTrue(loadBundled().isAvailable())
    }

    @Test
    fun `bundled resolve 8_8_8_8 returns Google`() = runTest {
        val result = loadBundled().resolve(InetAddress.getByName("8.8.8.8"))
        assertNotNull(result)
        assertEquals(15169, result!!.asn)
        assertTrue(result.name.contains("GOOGLE", ignoreCase = true),
            "Expected name containing GOOGLE, got: ${result.name}")
    }

    @Test
    fun `bundled resolve 1_1_1_1 returns Cloudflare`() = runTest {
        val result = loadBundled().resolve(InetAddress.getByName("1.1.1.1"))
        assertNotNull(result)
        assertEquals(13335, result!!.asn)
        assertTrue(result.name.contains("CLOUDFLARE", ignoreCase = true),
            "Expected name containing CLOUDFLARE, got: ${result.name}")
    }

    @Test
    fun `bundled resolve 208_67_222_222 returns Cisco or OpenDNS`() = runTest {
        val result = loadBundled().resolve(InetAddress.getByName("208.67.222.222"))
        assertNotNull(result)
        val name = result!!.name.uppercase()
        assertTrue(name.contains("CISCO") || name.contains("OPENDNS"),
            "Expected CISCO or OPENDNS, got: ${result.name}")
    }

    @Test
    fun `bundled resolve 10_0_0_1 private returns null`() = runTest {
        assertNull(loadBundled().resolve(InetAddress.getByName("10.0.0.1")))
    }

    @Test
    fun `bundled resolve 192_168_1_1 private returns null`() = runTest {
        assertNull(loadBundled().resolve(InetAddress.getByName("192.168.1.1")))
    }

    @Test
    fun `bundled resolve 127_0_0_1 loopback returns null`() = runTest {
        assertNull(loadBundled().resolve(InetAddress.getByName("127.0.0.1")))
    }

    @Test
    fun `bundled performance 10k resolves under 500ms`() = runTest {
        val resolver = loadBundled()
        val random = java.util.Random(42)
        val ips = (1..10_000).map {
            val bytes = ByteArray(4)
            random.nextBytes(bytes)
            // Avoid 0.x.x.x and 10.x.x.x and 127.x.x.x ranges for more realistic test
            bytes[0] = ((bytes[0].toInt() and 0xFF).coerceIn(11, 126)).toByte()
            InetAddress.getByAddress(bytes)
        }

        val start = System.nanoTime()
        for (ip in ips) {
            resolver.resolve(ip)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(elapsedMs < 500,
            "10,000 resolves took ${elapsedMs}ms, expected < 500ms")
    }

    @Test
    fun `bundled memory sanity - creation completes without OOM`() {
        // If this test reaches this point, fromResource() loaded ~22MB without OOM
        val resolver = loadBundled()
        assertTrue(resolver.size > 0)
    }

    // ── Helper functions ────────────────────────────────────────────────────

    @Test
    fun `ipToLong converts 4-byte IPv4 correctly`() {
        // 1.0.0.0 = 16777216
        assertEquals(16777216L, ipToLong(byteArrayOf(1, 0, 0, 0)))
        // 0.0.0.0
        assertEquals(0L, ipToLong(byteArrayOf(0, 0, 0, 0)))
        // 255.255.255.255
        assertEquals(4294967295L, ipToLong(byteArrayOf(-1, -1, -1, -1)))
        // 8.8.8.8 = 134744072
        assertEquals(134744072L, ipToLong(byteArrayOf(8, 8, 8, 8)))
    }

    @Test
    fun `ipToLong returns -1 for non-IPv4 byte array`() {
        assertEquals(-1L, ipToLong(byteArrayOf(1, 2, 3)))
        assertEquals(-1L, ipToLong(ByteArray(16)))
    }

    @Test
    fun `ipStringToLong converts dotted quad correctly`() {
        assertEquals(16777216L, ipStringToLong("1.0.0.0"))
        assertEquals(0L, ipStringToLong("0.0.0.0"))
        assertEquals(4294967295L, ipStringToLong("255.255.255.255"))
        assertEquals(134744072L, ipStringToLong("8.8.8.8"))
    }

    @Test
    fun `ipStringToLong returns -1 for invalid input`() {
        assertEquals(-1L, ipStringToLong("not-an-ip"))
        assertEquals(-1L, ipStringToLong("1.2.3"))
        assertEquals(-1L, ipStringToLong("1.2.3.4.5"))
        assertEquals(-1L, ipStringToLong("256.0.0.1"))
    }
}
