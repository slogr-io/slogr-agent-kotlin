package io.slogr.agent.native

import java.io.File
import java.io.FileOutputStream

/**
 * JNI bridge to libslogr-native.so.
 *
 * Library search order:
 *   1. System property  `slogr.native.dir`
 *   2. Environment var  `SLOGR_NATIVE_DIR`
 *   3. `/opt/slogr/lib`
 *   4. Extract from JAR resources → write to first writable dir from steps 1–3
 *   5. Throw [UnsatisfiedLinkError] with actionable message (no /tmp fallback)
 *
 * Only [JniProbeAdapter] should use this object directly.
 * All other modules depend on [NativeProbeAdapter] and never reference SlogrNative.
 */
object SlogrNative {

    private var loadError: Throwable? = null

    val isLoaded: Boolean get() = loadError == null

    init {
        try {
            loadLibrary()
        } catch (e: Throwable) {
            loadError = e
        }
    }

    /**
     * Throw [UnsatisfiedLinkError] with a clear message if the library did not
     * load. Call this in [JniProbeAdapter]'s constructor.
     */
    fun requireLoaded() {
        if (!isLoaded) throw UnsatisfiedLinkError(
            "libslogr-native.so could not be loaded: ${loadError?.message}. " +
            "Set SLOGR_NATIVE_DIR to the directory containing the library, " +
            "or place it in /opt/slogr/lib. " +
            "JNI probes require Linux with CAP_NET_RAW."
        )
    }

    // ── Library loading ───────────────────────────────────────────────────

    private fun loadLibrary() {
        val libName = if (isMusl()) "libslogr-native-musl.so" else "libslogr-native.so"

        val searchDirs = buildList {
            System.getProperty("slogr.native.dir")?.let { add(it) }
            System.getenv("SLOGR_NATIVE_DIR")?.let { add(it) }
            add("/opt/slogr/lib")
        }

        for (dir in searchDirs) {
            val f = File(dir, libName)
            if (f.exists() && f.canRead()) {
                System.load(f.absolutePath)
                return
            }
        }

        // Attempt to extract the .so bundled in the JAR resources
        val extracted = extractFromJar(libName, searchDirs)
        if (extracted != null) {
            System.load(extracted)
            return
        }

        throw UnsatisfiedLinkError(
            "$libName not found in: $searchDirs. " +
            "JAR resource /native/$libName also not available or no writable target directory. " +
            "Set SLOGR_NATIVE_DIR (or -Dslogr.native.dir) to a directory with execute permissions. " +
            "Do not use /tmp — it may be mounted noexec."
        )
    }

    private fun extractFromJar(libName: String, searchDirs: List<String>): String? {
        val stream = SlogrNative::class.java.getResourceAsStream("/native/$libName")
            ?: return null

        // Only extract to a configured dir (sysprop, env var, or /opt/slogr/lib).
        // Never extract to /tmp — enterprise environments often mount it noexec (ADR-008).
        val outDir = searchDirs.firstOrNull() ?: return null

        val outFile = File(outDir, libName)
        outFile.parentFile?.mkdirs()

        return try {
            stream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.setReadable(true)
            outFile.setExecutable(true)
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun isMusl(): Boolean =
        File("/lib/libc.musl-x86_64.so.1").exists() ||
        File("/lib/ld-musl-x86_64.so.1").exists()    ||
        File("/lib/ld-musl-aarch64.so.1").exists()

    // ── UDP socket operations (JNI) ───────────────────────────────────────

    external fun createSocket(): Int
    external fun createSocket6(): Int
    external fun closeSocket(fd: Int)
    external fun bindSocket(fd: Int, ip: Int, port: Int): Int
    external fun bindSocket6(fd: Int, ip: ByteArray, port: Short): Int
    external fun connectSocket(fd: Int, ip: ByteArray, port: Short): Int
    external fun setSocketOption(fd: Int, ttl: Int): Int
    external fun setSocketOption6(fd: Int, ttl: Int): Int
    external fun setSocketTos(fd: Int, tos: Short): Int
    external fun setSocketTos6(fd: Int, tos: Short): Int
    external fun setSocketTimeout(fd: Int, ms: Int): Int
    external fun sendTo(fd: Int, ip: ByteArray, port: Short, data: ByteArray, len: Int): Int
    /** Returns the local port of [fd] via getsockname(), or 0 on error. */
    external fun getLocalPort(fd: Int): Int
    external fun recvMsg(
        fd: Int, data: ByteArray, len: Int,
        ip: IntArray, port: ShortArray, ttl: ShortArray, tos: ShortArray
    ): Int

    // ── Traceroute probes (JNI) ───────────────────────────────────────────

    /**
     * ICMP ECHO probe at the given TTL.
     * @return RTT in microseconds (≥ 0), -1 on timeout, -2 on error
     * @param hopIpOut  4-byte output array: network-order IPv4 of responding router
     * @param metaOut   int[3]: [reached(0/1), icmpType, icmpCode]; -1 on timeout
     */
    external fun icmpProbe(
        targetIp: ByteArray, ttl: Int, timeoutMs: Int,
        hopIpOut: ByteArray, metaOut: IntArray
    ): Long

    /**
     * UDP probe at the given TTL.
     * Same return convention and output arrays as [icmpProbe].
     */
    external fun udpProbe(
        targetIp: ByteArray, targetPort: Int, ttl: Int, timeoutMs: Int,
        hopIpOut: ByteArray, metaOut: IntArray
    ): Long

    /**
     * TCP SYN probe at the given TTL.
     * Sends a SYN to [targetIp]:[destPort] (typically 443), listens for ICMP
     * Time Exceeded (intermediate hop) or SYN-ACK/RST (destination reached).
     * On SYN-ACK, sends RST to close cleanly.
     * Same return convention and output arrays as [icmpProbe].
     * metaOut[1] and metaOut[2] are -1 for TCP responses (no ICMP type/code).
     */
    external fun tcpProbe(
        targetIp: ByteArray, destPort: Int, ttl: Int, timeoutMs: Int,
        hopIpOut: ByteArray, metaOut: IntArray
    ): Long
}
