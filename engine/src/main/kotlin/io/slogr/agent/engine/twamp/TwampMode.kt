package io.slogr.agent.engine.twamp

import io.slogr.agent.engine.twamp.protocol.ServerStart

/**
 * Encapsulates TWAMP mode negotiation and per-session cryptographic state.
 *
 * BUG-D fix applied: [getTestSessionMode] allocates separate byte arrays for
 * [sendIv] and [receiveIv] instead of aliasing them to the same array.
 */
class TwampMode {

    var mode: Int = 0

    // Cryptographic state for the control session
    var aesKey: ByteArray? = null
    var hmacKey: ByteArray? = null
    var receiveIv: ByteArray = ByteArray(16)
    var sendIv: ByteArray = ByteArray(16)

    /**
     * Stored ServerStart reference used to chain HMAC across the first
     * AcceptTwSession in authenticated mode.
     */
    var serverStartMsg: ServerStart? = null

    /** Server octet from AcceptTwSession, reflected into test packet padding. */
    var serverOctet: Short = 0

    // ── Mode predicates ──────────────────────────────────────────────────────

    /**
     * Returns true when the control connection uses encryption/authentication
     * (modes 2, 4, or 8 — any mode except pure unauthenticated 1).
     */
    fun isControlEncrypted(): Boolean =
        (mode and AUTHENTICATED) != 0 ||
        (mode and ENCRYPTED)    != 0 ||
        (mode and MIXED_MODE)   != 0

    /** Returns true when TWAMP-Test packets are encrypted or authenticated. */
    fun isTestEncrypted(): Boolean =
        (mode and UNAUTHENTICATED) == 0 &&
        (mode and MIXED_MODE)      == 0

    /** Returns true when TWAMP-Test packets use authenticated mode (mode=2). */
    fun isTestAuthenticated(): Boolean = (mode and AUTHENTICATED) != 0

    /** Returns true when exactly one TWAMP mode bit is set (valid session mode). */
    fun isValidTwampMode(): Boolean {
        val count =
            ((mode and UNAUTHENTICATED) != 0).toInt() +
            ((mode and AUTHENTICATED)   != 0).toInt() +
            ((mode and ENCRYPTED)       != 0).toInt() +
            ((mode and MIXED_MODE)      != 0).toInt()
        return count == 1
    }

    fun isModeSupported(serverModes: Int): Boolean =
        (mode and serverModes and ALL_TWAMP_MODES) != 0

    fun isIndividualSessionControl(): Boolean = (mode and INDIVIDUAL_SESSION_CONTROL) != 0
    fun setIndividualSessionControl() { mode = mode or INDIVIDUAL_SESSION_CONTROL }

    fun isReflectOctet(): Boolean = (mode and REFLECT_OCTET) != 0
    fun setReflectOctet() { mode = mode or REFLECT_OCTET }

    fun isSymmetricalSize(): Boolean = (mode and SYMMETRICAL_SIZE) != 0
    fun setSymmetricalSize() { mode = mode or SYMMETRICAL_SIZE }

    /**
     * Returns the packet truncation length for the current mode (used to
     * compute symmetrical-size padding adjustment per RFC 6038).
     */
    fun pktTruncLength(): Int =
        if (isTestEncrypted()) TwampConstants.PKT_TRUNC_AUTH_ENC
        else TwampConstants.PKT_TRUNC_UNAUTH

    /**
     * Derive per-test-session [TwampMode] from the control session mode and
     * the assigned [SessionId].
     *
     * BUG-D fix: allocates [sendIv] and [receiveIv] as independent arrays
     * (the Java reference aliased them: `testMode.sendIV = testMode.receiveIV`).
     */
    fun getTestSessionMode(sid: SessionId): TwampMode {
        val testMode = TwampMode()
        testMode.mode = this.mode
        // BUG-D fix: separate arrays, both all-zeros for test sessions
        testMode.receiveIv = ByteArray(16)
        testMode.sendIv    = ByteArray(16)

        if (isTestEncrypted()) {
            val sidBytes = sid.toByteArray()
            testMode.aesKey  = io.slogr.agent.engine.twamp.auth.TwampCrypto.encryptAesEcb(
                requireNotNull(aesKey), sidBytes
            )
            testMode.hmacKey = io.slogr.agent.engine.twamp.auth.TwampCrypto.encryptAesCbc(
                requireNotNull(hmacKey), sidBytes, testMode.sendIv, updateIv = false
            )
        }
        return testMode
    }

    companion object {
        const val UNAUTHENTICATED         = 1 shl 0
        const val AUTHENTICATED           = 1 shl 1
        const val ENCRYPTED               = 1 shl 2
        const val MIXED_MODE              = 1 shl 3
        const val INDIVIDUAL_SESSION_CONTROL = 1 shl 4
        const val REFLECT_OCTET           = 1 shl 5
        const val SYMMETRICAL_SIZE        = 1 shl 6

        /** Bitmask covering all four basic TWAMP modes. */
        const val ALL_TWAMP_MODES = (1 shl 4) - 1

        /** Bitmask covering all capability bits. */
        const val ALL_CAPABILITY  = (1 shl 7) - 1
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0
