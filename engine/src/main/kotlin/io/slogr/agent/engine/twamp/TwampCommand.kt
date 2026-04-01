package io.slogr.agent.engine.twamp

/**
 * TWAMP-Control command numbers (first octet of each message after setup).
 * Values from RFC 5357 and RFC 5938.
 */
object TwampCommand {
    const val START_SESSIONS: Byte  = 2
    const val STOP_SESSIONS: Byte   = 3
    const val REQUEST_TW_SESSION: Byte = 5
    const val START_N_SESSION: Byte = 7
    const val START_N_ACK: Byte     = 8
    const val STOP_N_SESSION: Byte  = 9
    const val STOP_N_ACK: Byte      = 10
}

/**
 * TWAMP accept codes used in response messages.
 */
object TwampAccept {
    const val OK: Byte            = 0
    const val FAILURE: Byte       = 1
    const val NOT_SUPPORTED: Byte = 3
}
