package io.slogr.agent.engine.twamp.responder

import io.slogr.agent.engine.reflector.ReflectorThreadPool
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TWAMP responder — NIO Selector event loop.
 *
 * Listens on [listenPort] (default 862) for incoming controller connections.
 * Accepts each connection and runs a [TwampResponderSession] state machine on
 * the same single-threaded selector loop.
 *
 * **R2 change**: Test packet reflectors are no longer started as dedicated threads.
 * Instead a shared [ReflectorThreadPool] is created here and passed to each
 * [TwampResponderSession]. The pool is shut down when [stop] completes.
 *
 * **Usage:**
 * ```kotlin
 * val reflector = TwampReflector(adapter = jniAdapter)
 * reflector.start()
 * // …
 * reflector.stop()
 * ```
 *
 * @param adapter       UDP socket adapter for test reflectors.
 * @param listenPort    TWAMP control port (default 862).
 * @param bindIp        IP to bind the server socket (default wildcard).
 * @param allowlist     Optional IP allowlist for unauthenticated connections.
 * @param sharedSecret  Optional shared secret for authenticated mode.
 * @param agentIdBytes  Optional first 6 bytes of agent UUID (Slogr fingerprint).
 */
class TwampReflector(
    private val adapter: NativeProbeAdapter,
    private val listenPort: Int = 862,
    private val bindIp: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val allowlist: IpAllowlist = IpAllowlist(),
    private val sharedSecret: ByteArray? = null,
    private val agentIdBytes: ByteArray? = null,
    val isMeshMode: Boolean = false
) : Runnable {

    private val log = LoggerFactory.getLogger(TwampReflector::class.java)

    private val selector: Selector = Selector.open()
    private val sessionMap = HashMap<SelectionKey, TwampResponderSession>()

    /** Shared thread pool for all reflector sessions managed by this reflector instance. */
    val threadPool: ReflectorThreadPool = ReflectorThreadPool()

    @Volatile private var isAlive = false
    private var serverChannel: ServerSocketChannel? = null
    private lateinit var selectorThread: Thread

    private val readBuffer = ByteBuffer.allocateDirect(9216)

    // L3: Connection limits
    private val connectionsByIp = ConcurrentHashMap<String, AtomicInteger>()
    private val totalConnections = AtomicInteger(0)
    private val maxConnectionsPerIp = if (isMeshMode) 10 else 3
    private val maxTotalConnections = if (isMeshMode) 200 else 100

    // ── Public API ───────────────────────────────────────────────────────────

    fun start() {
        isAlive = true
        selectorThread = Thread(this, "twamp-reflector").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        isAlive = false
        selector.wakeup()
    }

    val actualPort: Int
        get() = (serverChannel?.localAddress as? InetSocketAddress)?.port ?: listenPort

    // ── Selector event loop ──────────────────────────────────────────────────

    override fun run() {
        log.info("TWAMP reflector thread started (port=$listenPort, bind=$bindIp)")
        try {
            val server = ServerSocketChannel.open()
            server.configureBlocking(false)
            server.socket().reuseAddress = true
            server.socket().bind(InetSocketAddress(bindIp, listenPort))
            server.register(selector, SelectionKey.OP_ACCEPT)
            serverChannel = server
            log.info("TWAMP reflector listening on ${server.localAddress}")

            while (isAlive || sessionMap.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) break
                selector.select(SELECT_TIMEOUT_MS)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    when {
                        key.isAcceptable -> handleAccept(key)
                        key.isReadable   -> handleRead(key)
                    }
                }
                sessionMap.entries.removeIf { (_, session) -> session.isClosed }
            }

            server.close()
            threadPool.shutdown()
            log.info("TWAMP reflector stopped")
        } catch (e: Throwable) {
            log.error("TWAMP reflector failed on port $listenPort: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private fun handleAccept(key: SelectionKey) {
        val serverChan = key.channel() as ServerSocketChannel
        val clientChan: SocketChannel = serverChan.accept() ?: return
        clientChan.configureBlocking(false)
        val clientIp = (clientChan.remoteAddress as InetSocketAddress).address
        val localIp  = (clientChan.localAddress  as InetSocketAddress).address

        // L3: Connection limits
        if (totalConnections.get() >= maxTotalConnections) {
            log.warn("Global connection limit reached ($maxTotalConnections) — rejecting $clientIp")
            clientChan.close(); return
        }
        val ipKey = clientIp.hostAddress
        val ipCount = connectionsByIp.getOrPut(ipKey) { AtomicInteger(0) }
        if (ipCount.get() >= maxConnectionsPerIp) {
            log.warn("Per-IP limit reached for $clientIp ($maxConnectionsPerIp) — rejecting")
            clientChan.close(); return
        }
        ipCount.incrementAndGet()
        totalConnections.incrementAndGet()

        val clientKey = clientChan.register(selector, SelectionKey.OP_READ)
        val session = TwampResponderSession(
            key          = clientKey,
            clientIp     = clientIp,
            localIp      = localIp,
            allowlist    = allowlist,
            sharedSecret = sharedSecret,
            adapter      = adapter,
            threadPool   = threadPool,
            agentIdBytes = agentIdBytes
        )
        sessionMap[clientKey] = session
        session.smStart()
        log.debug("Accepted TWAMP control connection from $clientIp")
    }

    @Synchronized
    private fun handleRead(key: SelectionKey) {
        val session = sessionMap[key] ?: return
        val chan = key.channel() as SocketChannel
        readBuffer.clear()
        val bytesRead = try {
            chan.read(readBuffer)
        } catch (e: Exception) {
            log.debug("Read error from ${chan.remoteAddress}: ${e.message}")
            -1
        }
        if (bytesRead < 0) {
            closeSession(key)
            return
        }
        readBuffer.flip()
        session.read(readBuffer)
        if (session.isClosed) closeSession(key)
    }

    private fun closeSession(key: SelectionKey) {
        val session = sessionMap.remove(key)
        if (session != null) {
            // L3: Release connection count
            val ipKey = session.clientIpAddress
            connectionsByIp[ipKey]?.decrementAndGet()
            totalConnections.decrementAndGet()
            session.close()
        }
        try { key.channel().close() } catch (_: Exception) {}
        key.cancel()
    }

    companion object {
        private const val SELECT_TIMEOUT_MS = 1000L
    }
}
