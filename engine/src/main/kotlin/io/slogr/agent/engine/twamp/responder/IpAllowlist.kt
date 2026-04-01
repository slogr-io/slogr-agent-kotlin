package io.slogr.agent.engine.twamp.responder

import java.net.InetAddress
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Thread-safe IP allowlist for unauthenticated TWAMP connections.
 *
 * An empty allowlist is treated as "disabled" — all IPs are permitted.
 * When non-empty, only listed IPs may connect.
 */
class IpAllowlist {

    private val addresses = HashSet<InetAddress>()
    private val lock = ReentrantReadWriteLock()

    fun add(ip: InetAddress) {
        lock.writeLock().lock()
        try { addresses.add(ip) }
        finally { lock.writeLock().unlock() }
    }

    fun clear() {
        lock.writeLock().lock()
        try { addresses.clear() }
        finally { lock.writeLock().unlock() }
    }

    /**
     * Returns true if [ip] is permitted. An empty allowlist permits all IPs.
     */
    fun isAllowed(ip: InetAddress): Boolean {
        lock.readLock().lock()
        return try {
            addresses.isEmpty() || addresses.contains(ip)
        } finally {
            lock.readLock().unlock()
        }
    }
}
