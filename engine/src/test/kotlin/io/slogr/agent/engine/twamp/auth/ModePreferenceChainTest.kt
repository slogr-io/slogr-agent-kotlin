package io.slogr.agent.engine.twamp.auth

import io.slogr.agent.engine.twamp.TwampMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModePreferenceChainTest {

    @Test fun `selects highest-preference mode supported by server`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.AUTHENTICATED)
            .prefer(TwampMode.UNAUTHENTICATED)
        // server supports only unauthenticated
        assertEquals(TwampMode.UNAUTHENTICATED, chain.selectMode(TwampMode.UNAUTHENTICATED))
    }

    @Test fun `selects first preference when server supports both`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.AUTHENTICATED)
            .prefer(TwampMode.UNAUTHENTICATED)
        // server supports both — should return AUTHENTICATED (higher preference)
        val serverCaps = TwampMode.AUTHENTICATED or TwampMode.UNAUTHENTICATED
        assertEquals(TwampMode.AUTHENTICATED, chain.selectMode(serverCaps))
    }

    @Test fun `returns null when no preferred mode is supported`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.ENCRYPTED)
        assertNull(chain.selectMode(TwampMode.UNAUTHENTICATED))
    }

    @Test fun `empty chain always returns null`() {
        assertNull(ModePreferenceChain().selectMode(TwampMode.ALL_TWAMP_MODES))
    }

    @Test fun `duplicate prefer entries are ignored`() {
        val chain = ModePreferenceChain()
            .prefer(TwampMode.UNAUTHENTICATED)
            .prefer(TwampMode.UNAUTHENTICATED)  // duplicate
        // still works normally
        assertEquals(TwampMode.UNAUTHENTICATED, chain.selectMode(TwampMode.UNAUTHENTICATED))
    }

    @Test fun `prefer returns this for fluent chaining`() {
        val chain = ModePreferenceChain()
        assertSame(chain, chain.prefer(TwampMode.UNAUTHENTICATED))
    }
}
