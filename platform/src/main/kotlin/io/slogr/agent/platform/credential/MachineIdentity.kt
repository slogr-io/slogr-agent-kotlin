package io.slogr.agent.platform.credential

import java.net.NetworkInterface

/**
 * Derives a stable machine fingerprint from the first non-loopback MAC address
 * and the hostname.
 *
 * Used as input to the key derivation function in [EncryptedCredentialStore].
 * NOT a security secret — protects against casual filesystem access, not against
 * an attacker who knows the machine identity.
 */
object MachineIdentity {

    /** Returns a stable string unique to this machine (MAC + hostname). */
    fun fingerprint(): String {
        val mac      = firstMac() ?: "00:00:00:00:00:00"
        val hostname = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("localhost")
        return "$mac|$hostname"
    }

    private fun firstMac(): String? {
        return NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { !it.isLoopback && it.hardwareAddress != null }
            ?.map { ni ->
                ni.hardwareAddress.joinToString(":") { "%02x".format(it) }
            }
            ?.firstOrNull()
    }
}
