package com.example

import com.example.data.model.GeoPointDto
import com.example.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun testHaversineDistance() {
        // Svendborg (55.0601, 10.6083) to Faaborg (55.0970, 10.2450) is ~23 km as the crow flies
        val d = GeoUtils.distanceMeters(55.0601, 10.6083, 55.0970, 10.2450)
        assertTrue("Distance should be between 20km and 26km", d in 20000.0..26000.0)
    }

    @Test
    fun testPolylineLengthCalculation() {
        val points = listOf(
            GeoPointDto(55.0, 10.0),
            GeoPointDto(55.0, 10.1),
            GeoPointDto(55.0, 10.2)
        )
        val lengthKm = GeoUtils.calculatePolylineLengthKm(points)
        assertTrue("Length should be positive", lengthKm > 10.0)
    }

    @Test
    fun testSnapPointToPolyline() {
        val polyline = listOf(
            GeoPointDto(55.0, 10.0),
            GeoPointDto(55.0, 10.2)
        )
        val testPt = GeoPointDto(55.01, 10.1) // slightly north of middle
        val snapped = GeoUtils.snapPointToPolyline(testPt, polyline)

        assertEquals(55.0, snapped.lat, 0.001)
        assertEquals(10.1, snapped.lng, 0.005)
    }

    @Test
    fun testDistanceAlongPolyline() {
        val polyline = listOf(
            GeoPointDto(55.0, 10.0),
            GeoPointDto(55.0, 10.1),
            GeoPointDto(55.0, 10.2)
        )
        val midPt = GeoPointDto(55.0, 10.1)
        val distKm = GeoUtils.distanceAlongPolylineKm(polyline, midPt)
        val totalKm = GeoUtils.calculatePolylineLengthKm(polyline)

        assertTrue(distKm > 0.0)
        assertTrue(distKm < totalKm)
        assertEquals(totalKm / 2.0, distKm, 0.5)
    }

    @Test
    fun testSlicePolylineKm() {
        val polyline = listOf(
            GeoPointDto(55.0, 10.0),
            GeoPointDto(55.0, 10.1),
            GeoPointDto(55.0, 10.2)
        )
        val totalKm = GeoUtils.calculatePolylineLengthKm(polyline)
        val sliced = GeoUtils.slicePolylineKm(polyline, 0.0, totalKm / 2.0)

        assertTrue("Sliced points must not be empty", sliced.isNotEmpty())
        val slicedKm = GeoUtils.calculatePolylineLengthKm(sliced)
        assertEquals(totalKm / 2.0, slicedKm, 0.5)
    }
}
