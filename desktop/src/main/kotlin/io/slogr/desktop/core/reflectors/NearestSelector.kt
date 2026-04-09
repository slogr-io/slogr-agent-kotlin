package io.slogr.desktop.core.reflectors

import kotlin.math.*

object NearestSelector {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Select the [maxCount] nearest reflectors to ([userLat], [userLon])
     * using haversine distance.
     */
    fun selectNearest(
        reflectors: List<Reflector>,
        userLat: Double,
        userLon: Double,
        maxCount: Int,
    ): List<Reflector> {
        return reflectors
            .map { it to haversineKm(userLat, userLon, it.latitude, it.longitude) }
            .sortedBy { it.second }
            .take(maxCount)
            .map { it.first }
    }

    /**
     * Great-circle distance in kilometres between two points on the globe.
     */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_KM * c
    }
}
