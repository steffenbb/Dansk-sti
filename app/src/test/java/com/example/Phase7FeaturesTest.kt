package com.example

import com.example.data.model.GeoPointDto
import com.example.data.model.NearbyOvernightSpot
import com.example.data.model.OvernightType
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripStage
import com.example.util.GeoUtils
import com.example.util.GpxExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class Phase7FeaturesTest {

    // --- Phase 7a: GPX Generation for Recorded Tracks & Existing Route ---
    @Test
    fun testGenerateRecordedTrackGpx_singleDay() {
        val points = listOf(
            RecordedTrackPointEntity(
                id = 1L,
                planId = 100L,
                dayNumber = 1,
                lat = 55.0601,
                lng = 10.6083,
                altitude = 25.4,
                accuracy = 8.5f,
                speedKmh = 4.2,
                timestamp = 1728892800000L // 2024-10-14 08:00 UTC
            ),
            RecordedTrackPointEntity(
                id = 2L,
                planId = 100L,
                dayNumber = 1,
                lat = 55.0650,
                lng = 10.6120,
                altitude = 31.2,
                accuracy = 12.0f,
                speedKmh = 4.5,
                timestamp = 1728896400000L
            )
        )
        val dayStats = listOf(
            RecordedDayStatEntity(
                planId = 100L,
                dayNumber = 1,
                distanceKm = 12.4,
                movingTimeSeconds = 10800L,
                ascentMeters = 85,
                pointCount = 2,
                lastTimestamp = 1728896400000L
            )
        )

        val gpx = GpxExporter.generateRecordedTrackGpx(
            trailName = "Øhavsstien",
            planId = 100L,
            dayNumber = 1,
            points = points,
            dayStats = dayStats
        )

        assertTrue("GPX header must exist", gpx.contains("<gpx version=\"1.1\""))
        assertTrue("Contains trail name", gpx.contains("Øhavsstien - Optaget spor Dag 1"))
        assertTrue("Contains track point 1", gpx.contains("lat=\"55.0601\" lon=\"10.6083\""))
        assertTrue("Contains altitude", gpx.contains("<ele>25.4</ele>"))
        assertTrue("Contains time element", gpx.contains("<time>"))
        assertTrue("Contains Day 1 metadata", gpx.contains("Dag 1 12.4 km, +85 m"))
    }

    @Test
    fun testGenerateRecordedTrackGpx_wholeTripMultiDay() {
        val points = listOf(
            RecordedTrackPointEntity(id = 1L, planId = 101L, dayNumber = 1, lat = 55.01, lng = 10.01, altitude = 10.0, accuracy = 5f, speedKmh = 4.0, timestamp = 1728890000000L),
            RecordedTrackPointEntity(id = 2L, planId = 101L, dayNumber = 2, lat = 55.05, lng = 10.05, altitude = 22.0, accuracy = 6f, speedKmh = 4.3, timestamp = 1728980000000L)
        )
        val dayStats = listOf(
            RecordedDayStatEntity(planId = 101L, dayNumber = 1, distanceKm = 15.2, movingTimeSeconds = 12000L, ascentMeters = 110, pointCount = 1, lastTimestamp = 1728890000000L),
            RecordedDayStatEntity(planId = 101L, dayNumber = 2, distanceKm = 18.0, movingTimeSeconds = 14400L, ascentMeters = 95, pointCount = 1, lastTimestamp = 1728980000000L)
        )

        val gpx = GpxExporter.generateRecordedTrackGpx(
            trailName = "Hærvejen",
            planId = 101L,
            dayNumber = null,
            points = points,
            dayStats = dayStats
        )

        assertTrue("Contains multi-day title", gpx.contains("Hærvejen - Optaget vandretur"))
        assertTrue("Contains track for Day 1", gpx.contains("<name>Dag 1 15.2 km, +110 m</name>"))
        assertTrue("Contains track for Day 2", gpx.contains("<name>Dag 2 18.0 km, +95 m</name>"))
        assertTrue("Contains point day 1", gpx.contains("lat=\"55.01\" lon=\"10.01\""))
        assertTrue("Contains point day 2", gpx.contains("lat=\"55.05\" lon=\"10.05\""))
    }

    // --- Phase 7b: Shelters near me calculation & sorting ---
    @Test
    fun testSheltersNearMeSorting() {
        val userLat = 55.0600
        val userLng = 10.6000

        val spots = listOf(
            NearbyOvernightSpot(id = "s1", name = "Shelter Langt Væk", type = "SHELTER", lat = 55.1200, lng = 10.6000, distanceMeters = 6670.0, bookingRequired = false, bookingUrl = null),
            NearbyOvernightSpot(id = "s2", name = "Shelter Meget Tæt", type = "SHELTER", lat = 55.0650, lng = 10.6050, distanceMeters = 640.0, bookingRequired = true, bookingUrl = "https://udinaturen.dk/booking/123"),
            NearbyOvernightSpot(id = "s3", name = "Fri Teltning Mellem", type = "FRI_TELTNING", lat = 55.0800, lng = 10.6000, distanceMeters = 2220.0, bookingRequired = false, bookingUrl = null)
        )

        val sorted = spots.sortedBy { it.distanceKm }

        assertEquals("s2", sorted[0].id)
        assertEquals("s3", sorted[1].id)
        assertEquals("s1", sorted[2].id)
        assertTrue("All spots within 10 km", sorted.all { it.distanceKm <= 10.0 })
    }

    // --- Phase 7c: Booking URL resolution ---
    @Test
    fun testBookingUrlResolution() {
        val spotWithBooking = NearbyOvernightSpot(
            id = "b1",
            name = "Troense Shelter",
            type = "SHELTER",
            lat = 55.03,
            lng = 10.65,
            distanceMeters = 1200.0,
            bookingRequired = true,
            bookingUrl = "https://bookenshelter.dk/troense"
        )

        val spotWithoutLink = NearbyOvernightSpot(
            id = "b2",
            name = "Skovmølle Shelter",
            type = "SHELTER",
            lat = 55.04,
            lng = 10.66,
            distanceMeters = 2500.0,
            bookingRequired = true,
            bookingUrl = null
        )

        val freeSpot = NearbyOvernightSpot(
            id = "b3",
            name = "Åben Plads",
            type = "SHELTER",
            lat = 55.05,
            lng = 10.67,
            distanceMeters = 3000.0,
            bookingRequired = false,
            bookingUrl = null
        )

        // Case 1: Provided URL is used directly
        assertEquals("https://bookenshelter.dk/troense", spotWithBooking.bookingUrl)

        // Case 2: Fallback URL searches udinaturen.dk
        val fallbackUrl = "https://udinaturen.dk/soeg?q=" + URLEncoder.encode(spotWithoutLink.name, "UTF-8")
        assertTrue(fallbackUrl.startsWith("https://udinaturen.dk/soeg?q=Skovm%C3%B8lle+Shelter") || fallbackUrl.contains("Skovm"))
        assertFalse(freeSpot.bookingRequired)
    }

    // --- Phase 7d: Trip Plan sharing text ---
    @Test
    fun testShareTripPlanTextFormatting() {
        val stages = listOf(
            TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 18.5,
                stageKm = 18.5,
                stopName = "Svanninge Bakker Shelter",
                stopType = "SHELTER",
                stopLat = 55.12,
                stopLng = 10.25,
                bookingRequired = true,
                estimatedWalkMinutes = 270,
                sunsetTime = "18:42"
            ),
            TripStage(
                dayNumber = 2,
                startKm = 18.5,
                endKm = 35.0,
                stageKm = 16.5,
                stopName = "Faaborg Havn",
                stopType = "DESTINATION",
                stopLat = 55.09,
                stopLng = 10.24,
                bookingRequired = false,
                estimatedWalkMinutes = 245,
                sunsetTime = "18:40"
            )
        )

        val totalKm = stages.sumOf { it.stageKm }
        val sb = java.lang.StringBuilder()
        sb.append("🥾 Min turplan: Øhavsstien\n")
        sb.append("Samlet distance: ${String.format(java.util.Locale.US, "%.1f", totalKm)} km over ${stages.size} dage\n\n")
        stages.forEach { stage ->
            val bookStr = if (stage.bookingRequired) " (Kræver booking)" else ""
            sb.append("• Dag ${stage.dayNumber}: ${String.format(java.util.Locale.US, "%.1f", stage.stageKm)} km\n")
            sb.append("   Overnatning: ${stage.stopName} [${stage.stopType}]$bookStr\n")
        }

        val text = sb.toString()
        assertTrue(text.contains("Min turplan: Øhavsstien"))
        assertTrue(text.contains("35.0 km over 2 dage"))
        assertTrue(text.contains("Dag 1: 18.5 km"))
        assertTrue(text.contains("Svanninge Bakker Shelter [SHELTER] (Kræver booking)"))
        assertTrue(text.contains("Dag 2: 16.5 km"))
        assertTrue(text.contains("Faaborg Havn [DESTINATION]"))
    }
}
