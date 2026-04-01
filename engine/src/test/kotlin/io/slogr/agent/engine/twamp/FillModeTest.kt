package io.slogr.agent.engine.twamp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FillModeTest {

    @Test fun `enum has exactly two values`() {
        assertEquals(2, FillMode.entries.size)
    }

    @Test fun `ZERO and RANDOM are present`() {
        assertNotNull(FillMode.valueOf("ZERO"))
        assertNotNull(FillMode.valueOf("RANDOM"))
    }
}
