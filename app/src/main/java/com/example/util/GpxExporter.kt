package com.example.util

import com.example.data.model.GeoPointDto
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxExporter {

    fun generateMultiSegmentGpx(
        trail: TrailEntity,
        plan: TripPlanEntity?,
        stages: List<TripStage>,
        trailPoints: List<GeoPointDto>,
        shelters: List<ShelterEntity> = emptyList(),
        hazards: List<UserHazardNoteEntity> = emptyList()
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val nowIso = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"DanskSti - Dansk Vandrerute &amp; Shelter Guide\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(trail.name)} - DanskSti Ruteplan</name>\n")
        sb.append("    <desc>${escapeXml(trail.description)}</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        // 1. Waypoints: Shelters
        shelters.forEach { sh ->
            sb.append("  <wpt lat=\"${sh.lat}\" lon=\"${sh.lng}\">\n")
            sb.append("    <name>${escapeXml(sh.name)}</name>\n")
            val capStr = sh.capacity?.toString() ?: "Ukendt"
            val ownerStr = sh.ownership ?: "Ukendt"
            sb.append("    <desc>${escapeXml(ownerStr)} • Plads: $capStr • Booking: ${if (sh.bookingRequired) "Ja" else "Fri"}</desc>\n")
            sb.append("    <sym>Campground</sym>\n")
            sb.append("    <type>Shelter</type>\n")
            sb.append("  </wpt>\n")
        }

        // 2. Waypoints: Stage Overnight Stops
        stages.forEach { stage ->
            if (stage.stopLat != 0.0 && stage.stopLng != 0.0) {
                sb.append("  <wpt lat=\"${stage.stopLat}\" lon=\"${stage.stopLng}\">\n")
                sb.append("    <name>Dag ${stage.dayNumber} Mål: ${escapeXml(stage.stopName)}</name>\n")
                sb.append("    <desc>Etape ${stage.dayNumber}: ${String.format(Locale.US, "%.1f", stage.stageKm)} km (${stage.stopType})</desc>\n")
                sb.append("    <sym>Flag, Blue</sym>\n")
                sb.append("    <type>StageEnd</type>\n")
                sb.append("  </wpt>\n")
            }
        }

        // 3. Waypoints: User Hazard Notes
        hazards.forEach { h ->
            sb.append("  <wpt lat=\"${h.lat}\" lon=\"${h.lng}\">\n")
            sb.append("    <name>Fare: ${escapeXml(h.title)}</name>\n")
            sb.append("    <desc>${escapeXml(h.description)}</desc>\n")
            sb.append("    <sym>Danger</sym>\n")
            sb.append("    <type>Hazard</type>\n")
            sb.append("  </wpt>\n")
        }

        // 4. Tracks: Multi-segment by Day if stages exist, or continuous track
        if (stages.isNotEmpty() && trailPoints.isNotEmpty()) {
            stages.forEach { stage ->
                sb.append("\n  <trk>\n")
                sb.append("    <name>Dag ${stage.dayNumber}: ${escapeXml(stage.stopName)} (${String.format(Locale.US, "%.1f", stage.stageKm)} km)</name>\n")
                sb.append("    <desc>Fra km ${String.format(Locale.US, "%.1f", stage.startKm)} til km ${String.format(Locale.US, "%.1f", stage.endKm)}</desc>\n")
                sb.append("    <trkseg>\n")

                val stagePts = GeoUtils.slicePolylineKm(trailPoints, stage.startKm, stage.endKm)
                stagePts.forEach { pt ->
                    sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\" />\n")
                }
                sb.append("    </trkseg>\n")
                sb.append("  </trk>\n")
            }
        } else if (trailPoints.isNotEmpty()) {
            sb.append("\n  <trk>\n")
            sb.append("    <name>${escapeXml(trail.name)}</name>\n")
            sb.append("    <trkseg>\n")
            trailPoints.forEach { pt ->
                sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\" />\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
