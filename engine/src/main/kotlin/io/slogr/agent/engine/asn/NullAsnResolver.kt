package io.slogr.agent.engine.asn

import io.slogr.agent.contracts.AsnInfo
import io.slogr.agent.contracts.interfaces.AsnResolver
import java.net.InetAddress

/** No-op [AsnResolver] that always returns null. Used when the MaxMind MMDB is unavailable. */
class NullAsnResolver : AsnResolver {
    override suspend fun resolve(ip: InetAddress): AsnInfo? = null
    fun isAvailable(): Boolean = false
}
