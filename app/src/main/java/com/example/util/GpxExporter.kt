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
        hazards: List<UserHazardNoteEntity> = emptyList(),
        customWaypoints: List<com.example.data.model.UserCustomWaypointEntity> = emptyList()
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

        // 4. Waypoints: User Custom Waypoints (Phase 12 Feature 6: Mine punkter)
        customWaypoints.forEach { wp ->
            sb.append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lng}\">\n")
            sb.append("    <name>${escapeXml(wp.name)}</name>\n")
            if (wp.note.isNotBlank()) {
                sb.append("    <desc>${escapeXml(wp.note)}</desc>\n")
            }
            sb.append("    <sym>Custom</sym>\n")
            sb.append("    <type>UserWaypoint</type>\n")
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

    /**
     * Generates a GPX 1.1 document for a custom or trail-based route from the Route Builder,
     * including route waypoints (overnight stops), user custom waypoints, and 3D track points.
     */
    fun generateCustomRouteGpx(
        routeName: String,
        description: String,
        routePoints: List<GeoPointDto>,
        overnightWaypoints: List<com.example.util.routing.RouteWaypoint> = emptyList(),
        customWaypoints: List<com.example.data.model.UserCustomWaypointEntity> = emptyList()
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
        sb.append("    <name>${escapeXml(routeName)} - DanskSti Ruteplan</name>\n")
        sb.append("    <desc>${escapeXml(description)}</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        overnightWaypoints.forEachIndexed { idx, wp ->
            sb.append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lng}\">\n")
            val stopLabel = wp.name?.takeIf { it.isNotBlank() } ?: "Lejr ${idx + 1}"
            sb.append("    <name>Dag ${idx + 1} Mål: ${escapeXml(stopLabel)}</name>\n")
            val spotDesc = wp.spotType ?: "Overnatning"
            sb.append("    <desc>${escapeXml(spotDesc)}</desc>\n")
            sb.append("    <sym>Campground</sym>\n")
            sb.append("    <type>Overnight</type>\n")
            sb.append("  </wpt>\n")
        }

        customWaypoints.forEach { wp ->
            sb.append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lng}\">\n")
            sb.append("    <name>${escapeXml(wp.name)}</name>\n")
            if (wp.note.isNotBlank()) {
                sb.append("    <desc>${escapeXml(wp.note)}</desc>\n")
            }
            sb.append("    <sym>Custom</sym>\n")
            sb.append("    <type>UserWaypoint</type>\n")
            sb.append("  </wpt>\n")
        }

        if (routePoints.isNotEmpty()) {
            sb.append("\n  <trk>\n")
            sb.append("    <name>${escapeXml(routeName)}</name>\n")
            sb.append("    <trkseg>\n")
            routePoints.forEach { pt ->
                val eleTag = pt.ele?.let { "<ele>${String.format(Locale.US, "%.1f", it)}</ele>" } ?: ""
                if (eleTag.isNotEmpty()) {
                    sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\">$eleTag</trkpt>\n")
                } else {
                    sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\" />\n")
                }
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Phase 7a: Generates GPX for recorded GPS tracks (either a specific day or whole trip).
     */
    fun generateRecordedTrackGpx(
        trailName: String,
        planId: Long,
        dayNumber: Int?,
        points: List<com.example.data.model.RecordedTrackPointEntity>,
        dayStats: List<com.example.data.model.RecordedDayStatEntity> = emptyList()
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val nowIso = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"DanskSti - Optaget GPS spor\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        sb.append("  <metadata>\n")
        val title = if (dayNumber != null) "$trailName - Optaget spor Dag $dayNumber" else "$trailName - Optaget vandretur"
        sb.append("    <name>${escapeXml(title)}</name>\n")
        sb.append("    <desc>GPS spor optaget i Dansk Sti appen</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        val grouped = points.groupBy { it.dayNumber }
        grouped.keys.sorted().forEach { day ->
            val dayPts = grouped[day] ?: emptyList()
            val stat = dayStats.find { it.dayNumber == day }
            val kmStr = stat?.let { String.format(Locale.US, "%.1f km", it.distanceKm) } ?: ""
            val ascentStr = stat?.let { ", +${it.ascentMeters} m" } ?: ""

            sb.append("  <trk>\n")
            sb.append("    <name>Dag $day $kmStr$ascentStr</name>\n")
            sb.append("    <trkseg>\n")
            dayPts.forEach { pt ->
                val timeIso = dateFormat.format(Date(pt.timestamp))
                val eleAttr = if (pt.altitude != null) "\n        <ele>${String.format(Locale.US, "%.1f", pt.altitude)}</ele>" else ""
                sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\">$eleAttr\n        <time>$timeIso</time>\n      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Writes GPX content to a file in cache/exports and shares it via Android Share Sheet using FileProvider.
     * Mime type: application/gpx+xml
     */
    fun exportAndShareGpxFile(
        context: android.content.Context,
        fileName: String,
        gpxContent: String,
        shareTitle: String
    ) {
        try {
            val exportDir = java.io.File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val cleanFileName = if (fileName.endsWith(".gpx")) fileName else "$fileName.gpx"
            val file = java.io.File(exportDir, cleanFileName)
            file.writeText(gpxContent, Charsets.UTF_8)

            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)

            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(shareTitle, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, shareTitle)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(shareIntent, shareTitle).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("GpxExporter", "Failed to export and share GPX file: ${e.message}", e)
        }
    }

    /**
     * Phase 12 Feature 7: Del rute as both GPX file and plain text summary.
     */
    fun exportAndShareGpxWithSummary(
        context: android.content.Context,
        fileName: String,
        gpxContent: String,
        shareTitle: String,
        textSummary: String
    ) {
        try {
            val exportDir = java.io.File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val file = java.io.File(exportDir, fileName)
            file.writeText(gpxContent, Charsets.UTF_8)

            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)

            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TEXT, textSummary)
                clipData = android.content.ClipData.newRawUri(shareTitle, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, shareTitle)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(shareIntent, shareTitle).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("GpxExporter", "Failed to export and share GPX with summary: ${e.message}", e)
        }
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
