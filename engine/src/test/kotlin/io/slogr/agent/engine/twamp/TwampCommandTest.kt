package io.slogr.agent.engine.twamp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwampCommandTest {

    @Test fun `command values match RFC 5357`() {
        assertEquals(2.toByte(),  TwampCommand.START_SESSIONS)
        assertEquals(3.toByte(),  TwampCommand.STOP_SESSIONS)
        assertEquals(5.toByte(),  TwampCommand.REQUEST_TW_SESSION)
        assertEquals(7.toByte(),  TwampCommand.START_N_SESSION)
        assertEquals(8.toByte(),  TwampCommand.START_N_ACK)
        assertEquals(9.toByte(),  TwampCommand.STOP_N_SESSION)
        assertEquals(10.toByte(), TwampCommand.STOP_N_ACK)
    }

    @Test fun `accept codes match RFC 5357`() {
        assertEquals(0.toByte(), TwampAccept.OK)
        assertEquals(1.toByte(), TwampAccept.FAILURE)
        assertEquals(3.toByte(), TwampAccept.NOT_SUPPORTED)
    }

    @Test fun `all command values are distinct`() {
        val values = listOf(
            TwampCommand.START_SESSIONS,
            TwampCommand.STOP_SESSIONS,
            TwampCommand.REQUEST_TW_SESSION,
            TwampCommand.START_N_SESSION,
            TwampCommand.START_N_ACK,
            TwampCommand.STOP_N_SESSION,
            TwampCommand.STOP_N_ACK,
        )
        assertEquals(values.size, values.distinct().size)
    }
}
