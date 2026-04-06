package io.slogr.desktop.core.reflectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearestSelectorTest {

    private val usEast = Reflector("1", "us-east", "aws", "1.1.1.1", 862, 39.0438, -77.4874, "free")
    private val euWest = Reflector("2", "eu-west", "aws", "2.2.2.2", 862, 53.3498, -6.2603, "free")
    private val apSoutheast = Reflector("3", "ap-southeast", "aws", "3.3.3.3", 862, 1.3521, 103.8198, "free")
    private val usWest = Reflector("4", "us-west", "aws", "4.4.4.4", 862, 45.5231, -122.6765, "paid")
    private val meSouth = Reflector("5", "me-south", "aws", "5.5.5.5", 862, 25.2770, 55.2962, "paid")

    private val all = listOf(usEast, euWest, apSoutheast, usWest, meSouth)

    // Karachi coordinates
    private val karachiLat = 24.8607
    private val karachiLon = 67.0011

    @Test
    fun `haversine returns zero for same point`() {
        val d = NearestSelector.haversineKm(0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `haversine London to New York is approximately 5570km`() {
        val d = NearestSelector.haversineKm(51.5074, -0.1278, 40.7128, -74.0060)
        assertTrue(d in 5500.0..5600.0, "Expected ~5570km, got $d")
    }

    @Test
    fun `haversine is symmetric`() {
        val ab = NearestSelector.haversineKm(0.0, 0.0, 45.0, 90.0)
        val ba = NearestSelector.haversineKm(45.0, 90.0, 0.0, 0.0)
        assertEquals(ab, ba, 0.001)
    }

    @Test
    fun `selectNearest from Karachi returns ME South as nearest`() {
        val nearest = NearestSelector.selectNearest(all, karachiLat, karachiLon, 1)
        assertEquals(1, nearest.size)
        assertEquals("me-south", nearest[0].region)
    }

    @Test
    fun `selectNearest from Karachi top 3 includes ME South and AP Southeast`() {
        val nearest = NearestSelector.selectNearest(all, karachiLat, karachiLon, 3)
        assertEquals(3, nearest.size)
        val regions = nearest.map { it.region }
        assertTrue("me-south" in regions, "ME South should be in top 3")
        assertTrue("ap-southeast" in regions, "AP Southeast should be in top 3")
    }

    @Test
    fun `selectNearest from US returns US reflectors first`() {
        val nearest = NearestSelector.selectNearest(all, 40.0, -74.0, 2)
        assertEquals("us-east", nearest[0].region)
        assertEquals("us-west", nearest[1].region)
    }

    @Test
    fun `selectNearest with maxCount larger than list returns all`() {
        val nearest = NearestSelector.selectNearest(all, 0.0, 0.0, 100)
        assertEquals(5, nearest.size)
    }

    @Test
    fun `selectNearest with empty list returns empty`() {
        val nearest = NearestSelector.selectNearest(emptyList(), 0.0, 0.0, 3)
        assertTrue(nearest.isEmpty())
    }
}
