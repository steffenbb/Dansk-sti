package com.example

import com.example.data.local.PreseededData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelterFriTeltningDataTest {

    @Test
    fun testAllTrailsHaveSheltersAndFriTeltning() {
        val trails = PreseededData.getInitialTrails()
        val shelters = PreseededData.getInitialShelters()
        val friTeltnings = PreseededData.getInitialFriTeltning()

        assertTrue("Should have at least 12 Danish national trails", trails.size >= 12)
        assertTrue("Should have extensive shelters database", shelters.size >= 25)
        assertTrue("Should have extensive Fri Teltning zones", friTeltnings.size >= 15)

        // Verify all 12 trails have associated shelters or fri teltning
        for (trail in trails) {
            val hasShelter = shelters.any { it.trailCorridorId == trail.id }
            val hasFriTeltning = friTeltnings.any { it.trailCorridorId == trail.id }
            assertTrue(
                "Trail ${trail.name} (${trail.id}) must have shelters or fri teltning zones",
                hasShelter || hasFriTeltning
            )
        }
    }

    @Test
    fun testShelterAttributesAreComplete() {
        val shelters = PreseededData.getInitialShelters()
        for (sh in shelters) {
            assertNotNull(sh.id)
            assertTrue("Shelter ${sh.name} has invalid lat", sh.lat in 54.0..58.0)
            assertTrue("Shelter ${sh.name} has invalid lng", sh.lng in 8.0..16.0)
            assertTrue("Shelter ${sh.name} must have positive capacity", sh.capacity > 0)
            assertFalse("Shelter ${sh.name} description cannot be empty", sh.description.isBlank())
            assertFalse("Shelter ${sh.name} shelterType cannot be empty", sh.shelterType.isBlank())
        }
    }

    @Test
    fun testFriTeltningAttributesAreComplete() {
        val friTeltnings = PreseededData.getInitialFriTeltning()
        for (ft in friTeltnings) {
            assertNotNull(ft.id)
            assertTrue("Fri teltning ${ft.name} has invalid centerLat", ft.centerLat in 54.0..58.0)
            assertTrue("Fri teltning ${ft.name} has invalid centerLng", ft.centerLng in 8.0..16.0)
            assertTrue("Fri teltning ${ft.name} must have rules summary", ft.rulesSummary.isNotBlank())
            assertTrue("Fri teltning ${ft.name} must have rulesUrl", ft.rulesUrl.startsWith("https://"))
            assertTrue("Fri teltning ${ft.name} must specify zoneType", ft.zoneType.isNotBlank())
            assertTrue("Fri teltning ${ft.name} must have polygon coordinates", ft.polygonJson.contains("lat"))
        }
    }
}
