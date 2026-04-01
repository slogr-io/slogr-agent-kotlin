package io.slogr.agent.engine.asn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress

class MaxMindAsnResolverTest {

    @Test
    fun `isAvailable returns false when MMDB file does not exist`() {
        val resolver = MaxMindAsnResolver(File("/nonexistent/GeoLite2-ASN.mmdb"))
        assertFalse(resolver.isAvailable())
    }

    @Test
    fun `resolve returns null when MMDB file does not exist`() = runTest {
        val resolver = MaxMindAsnResolver(File("/nonexistent/GeoLite2-ASN.mmdb"))
        assertNull(resolver.resolve(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `resolve returns null for private IPs even when DB unavailable`() = runTest {
        val resolver = MaxMindAsnResolver(File("/nonexistent/GeoLite2-ASN.mmdb"))
        assertNull(resolver.resolve(InetAddress.getByName("192.168.1.1")))
        assertNull(resolver.resolve(InetAddress.getByName("10.0.0.1")))
        assertNull(resolver.resolve(InetAddress.getByName("172.16.0.1")))
    }
}
