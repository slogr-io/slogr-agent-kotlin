package io.slogr.agent.platform.credential

import io.slogr.agent.platform.identity.PersistentFingerprint

/**
 * Derives a stable machine fingerprint for use in key derivation.
 *
 * Delegates to [PersistentFingerprint] which writes the fingerprint to disk on
 * first call and reads it back on subsequent calls, surviving container restarts
 * and VM clones (ADR-040 / R2-FP-01..04).
 */
object MachineIdentity {
    /** Returns a stable fingerprint unique to this machine. */
    fun fingerprint(): String = PersistentFingerprint.get()
}
