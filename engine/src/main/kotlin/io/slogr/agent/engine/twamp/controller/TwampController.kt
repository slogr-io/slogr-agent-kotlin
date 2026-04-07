package io.slogr.agent.engine.twamp.controller

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.ModePreferenceChain
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * TWAMP controller — NIO Selector event loop.
 *
 * Manages outgoing TWAMP control connections (one per measurement session).
 * Runs on a single daemon thread; all I/O is non-blocking.
 *
 * **Usage:**
 * ```kotlin
 * val controller = TwampController(adapter = jniAdapter)
 * controller.start()
 * controller.connect(
 *     reflectorIp = InetAddress.getByName("10.0.0.1"),
 *     config      = senderConfig,
 *     onComplete  = { result -> /* process result */ }
 * )
 * controller.stop()
 * ```
 *
 * @param adapter   UDP socket adapter for spawning test senders.
 * @param port      Reflector TWAMP control port (default 862).
 * @param localIp   Source IP for sender sockets (default wildcard).
 */
class TwampController(
    private val adapter: NativeProbeAdapter,
    private val port: Int = 862,
    private val localIp: InetAddress = InetAddress.getByName("0.0.0.0")
) : Runnable {

    private val log = LoggerFactory.getLogger(TwampController::class.java)

    private val selector: Selector = Selector.open()
    private val sessionMap = HashMap<SelectionKey, TwampControllerSession>()
    private val connectQueue = ConcurrentLinkedQueue<ConnectRequest>()

    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(4) { r ->
            Thread(r, "twamp-ctrl-scheduler").also { it.isDaemon = true }
        }

    @Volatile private var isAlive = true
    private lateinit var selectorThread: Thread

    private val readBuffer = ByteBuffer.allocateDirect(9216)

    // ── Public API ───────────────────────────────────────────────────────────

    fun start() {
        selectorThread = Thread(this, "twamp-controller").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        isAlive = false
        selector.wakeup()
    }

    /**
     * Request a connection to [reflectorIp]:[reflectorPort] for a new measurement session.
     * [onComplete] is called on the selector thread when all packets are exchanged.
     */
    fun connect(
        reflectorIp: InetAddress,
        reflectorPort: Int = port,
        config: SenderConfig,
        modeChain: ModePreferenceChain = ModePreferenceChain().prefer(TwampMode.UNAUTHENTICATED),
        sharedSecret: ByteArray? = null,
        onComplete: (SenderResult) -> Unit
    ) {
        connectQueue.add(ConnectRequest(reflectorIp, reflectorPort, config, modeChain, sharedSecret, onComplete))
        selector.wakeup()
    }

    // ── Selector event loop ──────────────────────────────────────────────────

    override fun run() {
        try {
            while (isAlive || sessionMap.isNotEmpty()) {
                processConnectQueue()
                selector.select(SELECT_TIMEOUT_MS)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    when {
                        key.isConnectable -> handleConnect(key)
                        key.isReadable    -> handleRead(key)
                    }
                }
                // Purge closed sessions
                sessionMap.entries.removeIf { (_, session) -> session.isClosed }
            }
            scheduler.shutdown()
        } catch (e: Throwable) {
            log.error("TwampController selector loop failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private fun processConnectQueue() {
        var req: ConnectRequest? = connectQueue.poll()
        while (req != null) {
            openConnection(req)
            req = connectQueue.poll()
        }
    }

    private fun openConnection(req: ConnectRequest) {
        try {
            val chan = SocketChannel.open()
            chan.configureBlocking(false)
            chan.connect(InetSocketAddress(req.reflectorIp, req.reflectorPort))
            val key = chan.register(selector, SelectionKey.OP_CONNECT)
            val session = TwampControllerSession(
                key          = key,
                reflectorIp  = req.reflectorIp,
                config       = req.config,
                modeChain    = req.modeChain,
                sharedSecret = req.sharedSecret,
                adapter      = adapter,
                scheduler    = scheduler,
                localIp      = localIp,
                onComplete   = req.onComplete
            )
            sessionMap[key] = session
        } catch (e: Exception) {
            log.error("Failed to open connection to ${req.reflectorIp}:${req.reflectorPort}: ${e.message}")
            req.onComplete(SenderResult(
                packets = emptyList(), packetsSent = 0, packetsRecv = 0,
                error = "connection failed: ${e.message}"
            ))
        }
    }

    @Synchronized
    private fun handleConnect(key: SelectionKey) {
        val chan = key.channel() as SocketChannel
        try {
            if (chan.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch (e: Exception) {
            log.error("Connect failed: ${e.message}")
            sessionMap[key]?.setCloseReason("TCP connect failed: ${e.message}")
            closeSession(key)
        }
    }

    @Synchronized
    private fun handleRead(key: SelectionKey) {
        val session = sessionMap[key] ?: return
        val chan = key.channel() as SocketChannel
        readBuffer.clear()
        val bytesRead = try {
            chan.read(readBuffer)
        } catch (e: Exception) {
            log.debug("Read error: ${e.message}")
            -1
        }
        if (bytesRead < 0) {
            session.setCloseReason("remote closed connection (EOF)")
            closeSession(key)
            return
        }
        readBuffer.flip()
        session.read(readBuffer)
        if (session.isClosed) closeSession(key)
    }

    private fun closeSession(key: SelectionKey) {
        val session = sessionMap.remove(key)
        session?.closeWithCallback()
        try { key.channel().close() } catch (_: Exception) {}
        key.cancel()
    }

    private data class ConnectRequest(
        val reflectorIp:  InetAddress,
        val reflectorPort: Int,
        val config:       SenderConfig,
        val modeChain:    ModePreferenceChain,
        val sharedSecret: ByteArray?,
        val onComplete:   (SenderResult) -> Unit
    )

    companion object {
        private const val SELECT_TIMEOUT_MS = 1000L
    }
}
