package com.example

import com.example.data.local.PreseededData
import com.example.data.model.GeoPointDto
import com.example.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2DataTest {

    @Test
    fun testPreseededTrailsOnlyContainMetadataAndRelationIds() {
        val trails = PreseededData.getInitialTrails()

        assertTrue("Should have at least 12 Danish national trails", trails.size >= 12)

        for (trail in trails) {
            assertNotNull(trail.id)
            assertTrue("Trail ${trail.name} id must start with trail_", trail.id.startsWith("trail_"))
            assertTrue("Trail ${trail.name} must have a name", trail.name.isNotBlank())
            assertTrue("Trail ${trail.name} must have a description", trail.description.isNotBlank())
            assertNotNull("Trail ${trail.name} must have an osmRelationId", trail.osmRelationId)
            assertTrue("Trail ${trail.name} osmRelationId must be positive", trail.osmRelationId!! > 0L)
        }

        val ohavsstien = trails.find { it.id == "trail_ohavsstien" }
        assertNotNull("Øhavsstien must be present", ohavsstien)
        assertEquals(1202410L, ohavsstien?.osmRelationId)
    }

    @Test
    fun testProjectPointOnPolyline() {
        val polyline = listOf(
            GeoPointDto(55.0, 10.0),
            GeoPointDto(55.0, 10.1),
            GeoPointDto(55.0, 10.2)
        )

        // Point directly on the middle
        val onPoint = GeoPointDto(55.0, 10.1)
        val (kmOnRoute1, detour1) = GeoUtils.projectPointOnPolyline(onPoint, polyline)
        val totalKm = GeoUtils.calculatePolylineLengthKm(polyline)
        assertEquals(totalKm / 2.0, kmOnRoute1, 0.2)
        assertEquals(0.0, detour1, 1.0)

        // Point 500m north of middle
        // ~0.0045 degrees lat is ~500m
        val offsetPoint = GeoPointDto(55.0045, 10.1)
        val (kmOnRoute2, detour2) = GeoUtils.projectPointOnPolyline(offsetPoint, polyline)
        assertEquals(totalKm / 2.0, kmOnRoute2, 0.2)
        assertTrue("Detour should be approximately 500m", detour2 in 450.0..550.0)
    }
}
