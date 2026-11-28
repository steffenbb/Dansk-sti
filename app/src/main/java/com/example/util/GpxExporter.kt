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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } // the Z means UTC
        val nowIso = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"DanskSti - Dansk Vandrerute &amp; Shelter Guide\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        val planTitle = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_route_plan_title_fmt, trail.name)
            ?: "${trail.name} - DanskSti Ruteplan"
        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(planTitle)}</name>\n")
        sb.append("    <desc>${escapeXml(trail.description)}</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        // 1. Waypoints: Shelters
        val unknownStr = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_unknown) ?: "Ukendt"
        val yesStr = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_booking_yes) ?: "Ja"
        val freeStr = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_booking_free) ?: "Fri"
        shelters.forEach { sh ->
            sb.append("  <wpt lat=\"${sh.lat}\" lon=\"${sh.lng}\">\n")
            sb.append("    <name>${escapeXml(sh.name)}</name>\n")
            val capStr = sh.capacity?.toString() ?: unknownStr
            val ownerStr = sh.ownership ?: unknownStr
            val bookingVal = if (sh.bookingRequired) yesStr else freeStr
            val shDesc = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_shelter_desc_fmt, ownerStr, capStr, bookingVal)
                ?: "$ownerStr • Plads: $capStr • Booking: $bookingVal"
            sb.append("    <desc>${escapeXml(shDesc)}</desc>\n")
            sb.append("    <sym>Campground</sym>\n")
            sb.append("    <type>Shelter</type>\n")
            sb.append("  </wpt>\n")
        }

        // 2. Waypoints: Stage Overnight Stops
        stages.forEach { stage ->
            if (stage.stopLat != 0.0 && stage.stopLng != 0.0) {
                val stageWptName = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_stage_goal_name_fmt, stage.dayNumber, stage.stopName)
                    ?: "Dag ${stage.dayNumber} Mål: ${stage.stopName}"
                val stageKmStr = String.format(Locale.US, "%.1f", stage.stageKm)
                val stageWptDesc = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_stage_desc_fmt, stage.dayNumber, stageKmStr, stage.stopType)
                    ?: "Etape ${stage.dayNumber}: $stageKmStr km (${stage.stopType})"
                sb.append("  <wpt lat=\"${stage.stopLat}\" lon=\"${stage.stopLng}\">\n")
                sb.append("    <name>${escapeXml(stageWptName)}</name>\n")
                sb.append("    <desc>${escapeXml(stageWptDesc)}</desc>\n")
                sb.append("    <sym>Flag, Blue</sym>\n")
                sb.append("    <type>StageEnd</type>\n")
                sb.append("  </wpt>\n")
            }
        }

        // 3. Waypoints: User Hazard Notes
        hazards.forEach { h ->
            val hazardName = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_hazard_name_fmt, h.title)
                ?: "Fare: ${h.title}"
            sb.append("  <wpt lat=\"${h.lat}\" lon=\"${h.lng}\">\n")
            sb.append("    <name>${escapeXml(hazardName)}</name>\n")
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
                val stageKmStr = String.format(Locale.US, "%.1f", stage.stageKm)
                val startKmStr = String.format(Locale.US, "%.1f", stage.startKm)
                val endKmStr = String.format(Locale.US, "%.1f", stage.endKm)
                val trkName = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_track_day_name_fmt, stage.dayNumber, stage.stopName, stageKmStr)
                    ?: "Dag ${stage.dayNumber}: ${stage.stopName} ($stageKmStr km)"
                val trkDesc = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_track_from_to_km_fmt, startKmStr, endKmStr)
                    ?: "Fra km $startKmStr til km $endKmStr"
                sb.append("\n  <trk>\n")
                sb.append("    <name>${escapeXml(trkName)}</name>\n")
                sb.append("    <desc>${escapeXml(trkDesc)}</desc>\n")
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } // the Z means UTC
        val nowIso = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"DanskSti - Dansk Vandrerute &amp; Shelter Guide\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        val customPlanTitle = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_route_plan_title_fmt, routeName)
            ?: "$routeName - DanskSti Ruteplan"
        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(customPlanTitle)}</name>\n")
        sb.append("    <desc>${escapeXml(description)}</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        overnightWaypoints.forEachIndexed { idx, wp ->
            sb.append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lng}\">\n")
            val stopLabel = wp.name?.takeIf { it.isNotBlank() }
                ?: AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_camp_default_fmt, idx + 1)
                ?: "Lejr ${idx + 1}"
            val wptName = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_stage_goal_name_fmt, idx + 1, stopLabel)
                ?: "Dag ${idx + 1} Mål: $stopLabel"
            sb.append("    <name>${escapeXml(wptName)}</name>\n")
            val spotDesc = wp.spotType
                ?: AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_overnight_default)
                ?: "Overnatning"
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } // the Z means UTC
        val nowIso = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"DanskSti - Optaget GPS spor\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        sb.append("  <metadata>\n")
        val title = if (dayNumber != null) {
            AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_recorded_day_title_fmt, trailName, dayNumber)
                ?: "$trailName - Optaget spor Dag $dayNumber"
        } else {
            AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_recorded_trip_title_fmt, trailName)
                ?: "$trailName - Optaget vandretur"
        }
        val recDesc = AppLocaleHelper.strOrNull(null, com.example.R.string.gpx_recorded_desc)
            ?: "GPS spor optaget i Dansk Sti appen"
        sb.append("    <name>${escapeXml(title)}</name>\n")
        sb.append("    <desc>${escapeXml(recDesc)}</desc>\n")
        sb.append("    <time>$nowIso</time>\n")
        sb.append("  </metadata>\n\n")

        val grouped = points.groupBy { it.dayNumber }
        grouped.keys.sorted().forEach { day ->
            val dayPts = grouped[day] ?: emptyList()
            val stat = dayStats.find { it.dayNumber == day }
            val kmStr = stat?.let { String.format(Locale.US, "%.1f km", it.distanceKm) } ?: ""
            val ascentStr = stat?.let { ", +${it.ascentMeters} m" } ?: ""
            val trkDayLabel = AppLocaleHelper.strOrNull(null, com.example.R.string.diary_share_day_heading_fmt, day) ?: "Dag $day"

            sb.append("  <trk>\n")
            sb.append("    <name>$trkDayLabel $kmStr$ascentStr</name>\n")
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
     * Safe GPX file-name helper:
     * - Replaces `/ \ : * ? " < > |` and control characters with `"_"`
     * - Trims to 80 characters
     * - Falls back to `"rute"` if empty
     */
    fun sanitizeGpxBaseName(rawName: String): String {
        val withoutExt = if (rawName.endsWith(".gpx", ignoreCase = true)) {
            rawName.dropLast(4)
        } else {
            rawName
        }
        val replaced = buildString(withoutExt.length) {
            for (ch in withoutExt) {
                if (ch.isISOControl() || ch in "/\\:*?\"<>|") {
                    append('_')
                } else {
                    append(ch)
                }
            }
        }.trim()
        val trimmed = if (replaced.length > 80) replaced.take(80).trim() else replaced
        return if (trimmed.isEmpty() || trimmed.all { it == '_' }) "rute" else trimmed
    }

    fun buildSafeGpxFileName(rawName: String, suffix: String = ""): String {
        val cleanSuffix = suffix.trim().removePrefix("_")
        val combined = if (cleanSuffix.isNotEmpty()) {
            val base = sanitizeGpxBaseName(rawName)
            sanitizeGpxBaseName("${base}_$cleanSuffix")
        } else {
            sanitizeGpxBaseName(rawName)
        }
        return "$combined.gpx"
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
            val cleanFileName = buildSafeGpxFileName(fileName)
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
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.example.R.string.toast_no_app_to_share_gpx),
                android.widget.Toast.LENGTH_SHORT
            ).show()
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
            val cleanFileName = buildSafeGpxFileName(fileName)
            val file = java.io.File(exportDir, cleanFileName)
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
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.example.R.string.toast_no_app_to_share_gpx),
                android.widget.Toast.LENGTH_SHORT
            ).show()
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
