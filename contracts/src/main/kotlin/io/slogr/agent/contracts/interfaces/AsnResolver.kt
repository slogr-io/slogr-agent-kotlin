package io.slogr.agent.contracts.interfaces

import io.slogr.agent.contracts.AsnInfo
import java.net.InetAddress

interface AsnResolver {
    /** Look up ASN information for the given IP. Returns null if not found or DB unavailable. */
    suspend fun resolve(ip: InetAddress): AsnInfo?
}
