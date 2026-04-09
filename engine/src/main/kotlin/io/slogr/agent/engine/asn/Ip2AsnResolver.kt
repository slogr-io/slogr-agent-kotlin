package io.slogr.agent.engine.asn

import io.slogr.agent.contracts.AsnInfo
import io.slogr.agent.contracts.interfaces.AsnResolver
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.zip.GZIPInputStream

/**
 * [AsnResolver] backed by the ip2asn TSV database from IPtoASN.com.
 *
 * Parses the TSV into a sorted array of IP ranges and performs O(log n)
 * binary search lookups. IPv4 only — IPv6 addresses return null.
 *
 * The database is public domain, ~3MB compressed / ~15MB uncompressed,
 * and covers all BGP-announced IPv4 prefixes (~700K entries).
 */
class Ip2AsnResolver(private val ranges: Array<IpRange>) : AsnResolver {

    private val log = LoggerFactory.getLogger(Ip2AsnResolver::class.java)

    fun isAvailable(): Boolean = ranges.isNotEmpty()

    /** Number of IP ranges in the dataset. */
    val size: Int get() = ranges.size

    override suspend fun resolve(ip: InetAddress): AsnInfo? {
        if (ip !is Inet4Address) return null
        if (ranges.isEmpty()) return null

        val target = ipToLong(ip.address)
        if (target < 0) return null

        // Binary search: find the last range where start <= target
        var lo = 0
        var hi = ranges.size - 1
        var result = -1

        while (lo <= hi) {
            val mid = lo + (hi - lo) / 2
            if (ranges[mid].start <= target) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        if (result < 0) return null
        val range = ranges[result]
        if (target > range.end) return null

        return AsnInfo(asn = range.asn, name = range.name)
    }

    companion object {
        private val log = LoggerFactory.getLogger(Ip2AsnResolver::class.java)

        /**
         * Parse ip2asn TSV lines into a sorted array of [IpRange].
         *
         * Format: `range_start\trange_end\tAS_number\tcountry_code\tAS_description`
         * Lines with ASN 0 (unassigned) are skipped.
         */
        fun parse(lines: Sequence<String>): Array<IpRange> {
            val result = mutableListOf<IpRange>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val parts = trimmed.split('\t')
                if (parts.size < 5) continue

                try {
                    val asn = parts[2].toIntOrNull() ?: continue
                    if (asn == 0) continue

                    val start = ipStringToLong(parts[0])
                    val end = ipStringToLong(parts[1])
                    if (start < 0 || end < 0 || start > end) continue

                    result.add(IpRange(start, end, asn, parts[4]))
                } catch (_: Exception) {
                    // Skip corrupt lines
                }
            }

            result.sortBy { it.start }
            return result.toTypedArray()
        }

        /**
         * Load from a TSV file on disk. Returns null if file is missing or corrupt.
         */
        fun fromFile(file: File): Ip2AsnResolver? {
            if (!file.exists()) {
                log.warn("ip2asn database not found at {}", file.absolutePath)
                return null
            }
            return try {
                val stream = if (file.name.endsWith(".gz")) {
                    GZIPInputStream(file.inputStream())
                } else {
                    file.inputStream()
                }
                val ranges = stream.bufferedReader().useLines { parse(it) }
                if (ranges.isEmpty()) {
                    log.warn("ip2asn database at {} parsed zero entries", file.absolutePath)
                    null
                } else {
                    log.info("ip2asn database loaded: {} entries from {}", ranges.size, file.absolutePath)
                    Ip2AsnResolver(ranges)
                }
            } catch (e: Exception) {
                log.error("Failed to parse ip2asn database '{}': {}", file.absolutePath, e.message)
                null
            }
        }

        /**
         * Load the bundled ip2asn-v4.tsv.gz from the classpath.
         * Returns null if the resource is missing or corrupt.
         */
        fun fromResource(): Ip2AsnResolver? {
            val resource = Ip2AsnResolver::class.java.getResourceAsStream("/ip2asn-v4.tsv.gz")
            if (resource == null) {
                log.warn("Bundled ip2asn-v4.tsv.gz not found on classpath")
                return null
            }
            return try {
                val ranges = GZIPInputStream(resource).bufferedReader().useLines { parse(it) }
                if (ranges.isEmpty()) {
                    log.warn("Bundled ip2asn database parsed zero entries")
                    null
                } else {
                    log.info("ip2asn database loaded from bundled resource: {} entries", ranges.size)
                    Ip2AsnResolver(ranges)
                }
            } catch (e: Exception) {
                log.error("Failed to load bundled ip2asn database: {}", e.message)
                null
            }
        }
    }
}

/** A single IP range entry from the ip2asn TSV database. */
data class IpRange(
    val start: Long,
    val end: Long,
    val asn: Int,
    val name: String
)

/** Convert a 4-byte IPv4 address to an unsigned Long. Returns -1 for non-IPv4. */
fun ipToLong(addr: ByteArray): Long {
    if (addr.size != 4) return -1
    return ((addr[0].toLong() and 0xFF) shl 24) or
            ((addr[1].toLong() and 0xFF) shl 16) or
            ((addr[2].toLong() and 0xFF) shl 8) or
            (addr[3].toLong() and 0xFF)
}

/** Parse a dotted-quad IPv4 string (e.g. "1.0.0.0") to an unsigned Long. Returns -1 on failure. */
fun ipStringToLong(ip: String): Long {
    val parts = ip.split('.')
    if (parts.size != 4) return -1
    return try {
        val a = parts[0].toLong()
        val b = parts[1].toLong()
        val c = parts[2].toLong()
        val d = parts[3].toLong()
        if (a !in 0..255 || b !in 0..255 || c !in 0..255 || d !in 0..255) return -1
        (a shl 24) or (b shl 16) or (c shl 8) or d
    } catch (_: NumberFormatException) {
        -1
    }
}
