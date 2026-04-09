package io.slogr.agent.engine.asn

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SwappableAsnResolverTest {

    private val googleRange = arrayOf(
        IpRange(start = ipStringToLong("8.8.4.0"),   end = ipStringToLong("8.8.4.255"),   asn = 15169, name = "GOOGLE"),
        IpRange(start = ipStringToLong("8.8.8.0"),   end = ipStringToLong("8.8.8.255"),   asn = 15169, name = "GOOGLE"),
        IpRange(start = ipStringToLong("104.16.0.0"), end = ipStringToLong("104.16.255.255"), asn = 13335, name = "CLOUDFLARENET")
    )

    private val cloudflareRange = arrayOf(
        IpRange(start = ipStringToLong("1.1.1.0"), end = ipStringToLong("1.1.1.255"), asn = 13335, name = "CLOUDFLARENET")
    )

    // ── 1. Default state ────────────────────────────────────────────────────

    @Test
    fun `default starts with NullAsnResolver - resolve returns null`() = runTest {
        val resolver = SwappableAsnResolver()
        assertNull(resolver.resolve(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `default starts with NullAsnResolver - isAvailable returns false`() {
        assertFalse(SwappableAsnResolver().isAvailable())
    }

    // ── 2. Swap to working resolver ─────────────────────────────────────────

    @Test
    fun `swap to Ip2AsnResolver enables resolve`() = runTest {
        val resolver = SwappableAsnResolver()
        resolver.swap(Ip2AsnResolver(googleRange))

        val result = resolver.resolve(InetAddress.getByName("8.8.8.8"))
        assertNotNull(result)
        assertEquals(15169, result!!.asn)
        assertEquals("GOOGLE", result.name)
    }

    @Test
    fun `swap to Ip2AsnResolver makes isAvailable true`() {
        val resolver = SwappableAsnResolver()
        resolver.swap(Ip2AsnResolver(googleRange))
        assertTrue(resolver.isAvailable())
    }

    // ── 3. Swap back to NullAsnResolver ─────────────────────────────────────

    @Test
    fun `swap back to NullAsnResolver disables resolve`() = runTest {
        val resolver = SwappableAsnResolver()
        resolver.swap(Ip2AsnResolver(googleRange))
        assertNotNull(resolver.resolve(InetAddress.getByName("8.8.8.8")))

        resolver.swap(NullAsnResolver())
        assertNull(resolver.resolve(InetAddress.getByName("8.8.8.8")))
        assertFalse(resolver.isAvailable())
    }

    // ── 4. Multiple rapid swaps ─────────────────────────────────────────────

    @Test
    fun `multiple rapid swaps - final resolver is active`() = runTest {
        val resolver = SwappableAsnResolver()

        resolver.swap(Ip2AsnResolver(googleRange))
        resolver.swap(NullAsnResolver())
        resolver.swap(Ip2AsnResolver(cloudflareRange))
        resolver.swap(NullAsnResolver())
        resolver.swap(Ip2AsnResolver(googleRange))

        // Final swap was to googleRange
        val result = resolver.resolve(InetAddress.getByName("8.8.8.8"))
        assertNotNull(result)
        assertEquals(15169, result!!.asn)

        // cloudflareRange IP should not resolve (googleRange doesn't cover 1.1.1.1)
        assertNull(resolver.resolve(InetAddress.getByName("1.1.1.1")))
    }

    // ── 5. isAvailable reflects current delegate ────────────────────────────

    @Test
    fun `isAvailable tracks each swap`() {
        val resolver = SwappableAsnResolver()
        assertFalse(resolver.isAvailable())

        resolver.swap(Ip2AsnResolver(googleRange))
        assertTrue(resolver.isAvailable())

        resolver.swap(NullAsnResolver())
        assertFalse(resolver.isAvailable())

        resolver.swap(Ip2AsnResolver(cloudflareRange))
        assertTrue(resolver.isAvailable())
    }

    // ── 6. IPv6 returns null after swap ─────────────────────────────────────

    @Test
    fun `resolve IPv6 returns null even with Ip2AsnResolver`() = runTest {
        val resolver = SwappableAsnResolver(Ip2AsnResolver(googleRange))
        assertNull(resolver.resolve(InetAddress.getByName("2001:4860:4860::8888")))
    }

    // ── 7. Concurrency safety ───────────────────────────────────────────────

    @Test
    fun `swap during concurrent resolves throws no exceptions`() = runTest {
        val resolver = SwappableAsnResolver(Ip2AsnResolver(googleRange))
        val ip = InetAddress.getByName("8.8.8.8")

        val job = launch {
            repeat(1000) { resolver.resolve(ip) }
        }

        // Swap mid-way through the resolve loop
        repeat(5) { i ->
            if (i % 2 == 0) resolver.swap(NullAsnResolver())
            else resolver.swap(Ip2AsnResolver(googleRange))
        }

        job.join() // No ConcurrentModificationException or NPE
    }

    // ── 8. Swap to same instance ────────────────────────────────────────────

    @Test
    fun `swap to same resolver instance is no-op`() = runTest {
        val ip2asn = Ip2AsnResolver(googleRange)
        val resolver = SwappableAsnResolver(ip2asn)

        resolver.swap(ip2asn) // same instance

        val result = resolver.resolve(InetAddress.getByName("8.8.8.8"))
        assertNotNull(result)
        assertEquals(15169, result!!.asn)
    }
}
