package io.slogr.agent.engine.asn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress

class NullAsnResolverTest {

    private val resolver = NullAsnResolver()

    @Test
    fun `resolve always returns null`() = runTest {
        assertNull(resolver.resolve(InetAddress.getByName("8.8.8.8")))
        assertNull(resolver.resolve(InetAddress.getByName("1.1.1.1")))
        assertNull(resolver.resolve(InetAddress.getByName("192.168.1.1")))
    }

    @Test
    fun `isAvailable returns false`() {
        assertFalse(resolver.isAvailable())
    }
}
