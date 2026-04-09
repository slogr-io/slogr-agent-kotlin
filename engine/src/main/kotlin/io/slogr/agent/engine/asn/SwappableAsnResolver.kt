package io.slogr.agent.engine.asn

import io.slogr.agent.contracts.AsnInfo
import io.slogr.agent.contracts.interfaces.AsnResolver
import java.net.InetAddress

/**
 * Thread-safe [AsnResolver] wrapper that allows runtime hot-swapping of the
 * underlying resolver without restarting the agent.
 *
 * Used in daemon mode where the ip2asn database refreshes periodically.
 * [@Volatile] ensures visibility across threads. No synchronization is needed
 * because [resolve] reads immutable data — the [Array] inside [Ip2AsnResolver]
 * never mutates after construction.
 */
class SwappableAsnResolver(initial: AsnResolver = NullAsnResolver()) : AsnResolver {

    @Volatile
    private var delegate: AsnResolver = initial

    fun swap(newResolver: AsnResolver) {
        delegate = newResolver
    }

    fun isAvailable(): Boolean {
        val d = delegate
        return when (d) {
            is Ip2AsnResolver -> d.isAvailable()
            is MaxMindAsnResolver -> d.isAvailable()
            is NullAsnResolver -> d.isAvailable()
            else -> true
        }
    }

    override suspend fun resolve(ip: InetAddress): AsnInfo? = delegate.resolve(ip)
}
