package io.slogr.agent.contracts

import io.mockk.mockk
import io.slogr.agent.contracts.interfaces.AsnResolver
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.contracts.interfaces.ResultPublisher
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Verifies all interfaces compile correctly and can be mocked.
 * Since these are pure declarations, the test confirms correct method signatures.
 */
class InterfacesTest {

    @Test
    fun `MeasurementEngine can be mocked`() {
        val engine = mockk<MeasurementEngine>()
        assertNotNull(engine)
    }

    @Test
    fun `ResultPublisher can be mocked`() {
        val publisher = mockk<ResultPublisher>()
        assertNotNull(publisher)
    }

    @Test
    fun `CredentialStore can be mocked`() {
        val store = mockk<CredentialStore>()
        assertNotNull(store)
    }

    @Test
    fun `AsnResolver can be mocked`() {
        val resolver = mockk<AsnResolver>()
        assertNotNull(resolver)
    }
}
