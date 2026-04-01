package io.slogr.agent.engine.asn

import com.maxmind.geoip2.DatabaseReader
import io.slogr.agent.contracts.AsnInfo
import io.slogr.agent.contracts.interfaces.AsnResolver
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

/**
 * [AsnResolver] backed by a local MaxMind GeoLite2-ASN MMDB file.
 *
 * Opens the file in memory-map mode for sub-millisecond lookups.
 * Degrades gracefully when the file is absent — [resolve] returns null,
 * [isAvailable] returns false.
 */
class MaxMindAsnResolver(dbFile: File) : AsnResolver {

    private val log = LoggerFactory.getLogger(MaxMindAsnResolver::class.java)

    private val reader: DatabaseReader? = openReader(dbFile)

    fun isAvailable(): Boolean = reader != null

    override suspend fun resolve(ip: InetAddress): AsnInfo? {
        val r = reader ?: return null
        return try {
            val response = r.asn(ip)
            AsnInfo(
                asn  = response.autonomousSystemNumber.toInt(),
                name = response.autonomousSystemOrganization ?: "Unknown"
            )
        } catch (_: Exception) {
            // IP not in DB (private ranges, CGNAT, multicast, etc.)
            null
        }
    }

    private fun openReader(dbFile: File): DatabaseReader? {
        if (!dbFile.exists()) {
            log.warn(
                "ASN database not found at {}. Traceroute will show IPs only. " +
                "Run 'slogr-agent setup-asn' to enable ASN enrichment.",
                dbFile.absolutePath
            )
            return null
        }

        val ageDays = Duration.between(
            Instant.ofEpochMilli(dbFile.lastModified()), Instant.now()
        ).toDays()
        if (ageDays > 90) {
            log.warn(
                "ASN database is {} days old. Run 'slogr-agent setup-asn' to refresh.",
                ageDays
            )
        }

        return try {
            DatabaseReader.Builder(dbFile).build()
        } catch (e: Exception) {
            log.error("Failed to open ASN database '{}': {}", dbFile.absolutePath, e.message)
            null
        }
    }
}
