package io.slogr.agent.engine.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Measures TCP connection time to a target host using [Socket.connect].
 *
 * Tries [ports] in order; returns the first successful connection time.
 * If all ports are refused or unreachable, returns [TcpConnectResult.skipped].
 *
 * No JNI needed — pure Java [Socket].
 */
class TcpConnectProbe {

    data class TcpConnectResult(
        /** TCP connect latency in milliseconds; null if the connection was not attempted/skipped. */
        val connectMs: Float?,
        /** Port that connected (443 or 80); null if skipped. */
        val port: Int?,
        /** True if all ports were refused or unreachable. */
        val skipped: Boolean
    ) {
        companion object {
            val SKIPPED = TcpConnectResult(connectMs = null, port = null, skipped = true)
        }
    }

    /**
     * Attempt a TCP connect to [target] on each port in [ports] (default: 443 then 80).
     * Returns the timing for the first port that accepts the connection.
     */
    suspend fun probe(
        target: InetAddress,
        ports: List<Int> = listOf(443, 80),
        timeoutMs: Int = 3000
    ): TcpConnectResult = withContext(Dispatchers.IO) {
        for (port in ports) {
            val result = tryConnect(target, port, timeoutMs)
            if (result != null) return@withContext TcpConnectResult(
                connectMs = result,
                port      = port,
                skipped   = false
            )
        }
        TcpConnectResult.SKIPPED
    }

    private fun tryConnect(target: InetAddress, port: Int, timeoutMs: Int): Float? {
        val socket = Socket()
        return try {
            val t0 = System.nanoTime()
            socket.connect(InetSocketAddress(target, port), timeoutMs)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000f
            elapsedMs
        } catch (_: ConnectException) {
            null  // port refused
        } catch (_: SocketTimeoutException) {
            null  // no response within timeout
        } catch (_: Exception) {
            null  // any other connectivity failure
        } finally {
            runCatching { socket.close() }
        }
    }
}
