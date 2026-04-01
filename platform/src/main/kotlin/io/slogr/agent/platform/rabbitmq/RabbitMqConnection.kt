package io.slogr.agent.platform.rabbitmq

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.slogr.agent.contracts.AgentCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Manages the AMQP connection lifecycle for a single agent.
 *
 * JWT auth: username = "", password = [AgentCredential.rabbitmqJwt].
 * Heartbeat: 30 seconds.
 * TLS: port 5671 (AMQPS).
 *
 * On disconnect, starts an exponential-backoff reconnect loop:
 * 3 → 6 → 12 → 24 → 48 → 60 s (capped).
 */
class RabbitMqConnection(
    private val credential: AgentCredential,
    private val onConnected: suspend () -> Unit = {},
    private val onDisconnected: suspend () -> Unit = {}
) {
    private val log = LoggerFactory.getLogger(RabbitMqConnection::class.java)

    @Volatile private var connection: Connection? = null
    @Volatile private var channel: Channel? = null

    /** Open a new connection and channel. Returns the ready channel. */
    fun connect(): Channel {
        val factory = buildFactory(credential.rabbitmqJwt)
        val conn = factory.newConnection("slogr-agent-${credential.agentId}")
        val ch   = conn.createChannel()
        ch.confirmSelect()           // publisher confirms
        conn.addShutdownListener { cause ->
            log.warn("RabbitMQ connection closed: ${cause.message}")
        }
        connection = conn
        channel    = ch
        log.info("RabbitMQ connected to ${credential.rabbitmqHost}:${credential.rabbitmqPort}")
        return ch
    }

    /**
     * Returns the live channel if connected, null otherwise.
     * Callers should call [connect] or wait for [reconnectLoop] to restore it.
     */
    fun channel(): Channel? = channel?.takeIf { it.isOpen }

    /** Start a reconnect loop in [scope]. Calls [onConnected] / [onDisconnected] on transitions. */
    fun reconnectLoop(scope: CoroutineScope, jwtRefresher: suspend () -> String?) {
        scope.launch {
            val backoffMs = longArrayOf(3_000, 6_000, 12_000, 24_000, 48_000, 60_000)
            var attempt   = 0
            while (isActive) {
                val ch = channel()
                if (ch != null) {
                    attempt = 0
                    delay(5_000)
                    continue
                }
                onDisconnected()
                val waitMs = backoffMs[attempt.coerceAtMost(backoffMs.size - 1)]
                log.info("RabbitMQ reconnect attempt ${attempt + 1} in ${waitMs}ms")
                delay(waitMs)

                val refreshedJwt = runCatching { jwtRefresher() }.getOrNull()
                val jwt = refreshedJwt ?: credential.rabbitmqJwt

                runCatching {
                    val factory = buildFactory(jwt)
                    val conn    = factory.newConnection("slogr-agent-${credential.agentId}")
                    val newCh   = conn.createChannel()
                    newCh.confirmSelect()
                    connection = conn
                    channel    = newCh
                    log.info("RabbitMQ reconnected (attempt ${attempt + 1})")
                    onConnected()
                    attempt = 0
                }.onFailure { e ->
                    log.warn("RabbitMQ reconnect failed: ${e.message}")
                    attempt++
                }
            }
        }
    }

    /** Update the JWT (after refresh) without reconnecting. */
    fun updateJwt(newJwt: String) {
        // JWT is sent in connection open — changing it takes effect on next reconnect.
        // This method is a hook for [TokenRefresher] to coordinate the next reconnect.
        log.debug("RabbitMQ JWT updated (takes effect on next reconnect)")
    }

    fun close() {
        runCatching { channel?.close() }
        runCatching { connection?.close() }
        channel    = null
        connection = null
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun buildFactory(jwt: String): ConnectionFactory {
        val factory = ConnectionFactory()
        factory.host      = credential.rabbitmqHost
        factory.port      = credential.rabbitmqPort
        factory.username  = ""       // JWT PLAIN: empty username, JWT as password
        factory.password  = jwt
        factory.isAutomaticRecoveryEnabled = false   // we manage reconnects
        factory.requestedHeartbeat = 30
        if (credential.rabbitmqPort == 5671) {
            factory.useSslProtocol()
        }
        return factory
    }
}
