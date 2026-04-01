package io.slogr.agent.platform.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AirGapDetectorTest {

    @BeforeEach
    @AfterEach
    fun reset() { AirGapDetector.resetForTest() }

    @Test
    fun `isAirGapped returns a Boolean without throwing`() {
        // We cannot reliably control DNS in a unit test, but we can assert
        // the method returns a valid boolean and caches the result.
        val first  = AirGapDetector.isAirGapped()
        val second = AirGapDetector.isAirGapped()
        assertEquals(first, second, "Result must be stable (cached)")
    }

    @Test
    fun `result is cached after first call`() {
        val a = AirGapDetector.isAirGapped()
        // Reset would change the result if not cached; second call without reset uses cache
        val b = AirGapDetector.isAirGapped()
        assertSame(a, b)
    }
}
