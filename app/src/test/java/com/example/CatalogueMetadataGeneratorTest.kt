package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TrailSegmentType
import com.example.data.remote.OverpassClient
import com.example.util.GeoUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Manual generator: only runs with -PgenerateCatalogue=true.
 * Fetches each catalogue trail with the app's own TrailGeometryRepository and writes
 * app/src/main/assets/catalogue/catalogue_metadata.json. Progress and failures are written to
 * app/build/catalogue-generator-log.txt and to standard output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogueMetadataGeneratorTest {

    private lateinit var context: Context
    private var logFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun log(message: String) {
        println("[catalogue] $message")
        runCatching { logFile?.appendText(message + "\n") }
    }

    private fun resolveCatalogueMetadataOutputFile(): File {
        val candidates = listOf(
            File("src/main/assets/catalogue/catalogue_metadata.json"),
            File("app/src/main/assets/catalogue/catalogue_metadata.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: candidates.firstOrNull { it.parentFile?.exists() == true }
            ?: File("app/src/main/assets/catalogue/catalogue_metadata.json")
    }

    private suspend fun queryNearestOsmTown(lat: Double, lng: Double): String? {
        val query = """
            [out:json][timeout:15];
            node["place"~"^(city|town|village|hamlet)$"]["name"](around:15000,$lat,$lng);
            out body;
        """.trimIndent()
        val raw = runCatching {
            OverpassClient.queryRawJson(query, priority = OverpassClient.Priority.BACKGROUND)
        }.onFailure { log("    nearest town lookup failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val elements = root.optJSONArray("elements") ?: return null
        var bestName: String? = null
        var bestDistM = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val nLat = el.optDouble("lat", Double.NaN)
            val nLng = el.optDouble("lon", Double.NaN)
            if (nLat.isNaN() || nLng.isNaN()) continue
            val name = el.optJSONObject("tags")?.optString("name", "")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val d = GeoUtils.distanceMeters(lat, lng, nLat, nLng)
            if (d < bestDistM) {
                bestDistM = d
                bestName = name
            }
        }
        return bestName
    }

    @Test
    fun generateCatalogueMetadataFromOverpassUsingRealStitcher() = runBlocking {
        Assume.assumeTrue(
            "Manual generator test: run with -PgenerateCatalogue=true",
            System.getProperty("generateCatalogue") == "true"
        )

        logFile = File("build/catalogue-generator-log.txt").also {
            it.parentFile?.mkdirs()
            it.writeText("")
        }

        val app = context as DanskStiApp
        val repo = app.repository

        // Robolectric reports no internet-capable network by default, which makes the app's
        // connectivity check skip every Overpass request. This run has real internet.
        repo.isOnlineOverrideForTests = true
        try {
            repo.initializePreseededDataIfEmpty()
            val trails = app.database.dao().getAllTrailsList()
            log("Catalogue trails: ${trails.size} (with OSM relation: ${trails.count { it.osmRelationId != null }})")

            val trailsJson = JSONArray()
            val failed = mutableListOf<String>()
            val skippedNoRelation = mutableListOf<String>()

            for ((index, trail) in trails.withIndex()) {
                val relId = trail.osmRelationId
                if (relId == null) {
                    skippedNoRelation += trail.id
                    log("[${index + 1}/${trails.size}] ${trail.id}: no OSM relation id, skipped")
                    continue
                }
                if (index > 0) delay(5_000L)

                var lastStage = ""
                var dto: com.example.data.model.TrailGeometryDto? = null
                for (attempt in 1..3) {
                    log("[${index + 1}/${trails.size}] ${trail.id} (relation $relId), attempt $attempt")
                    dto = runCatching {
                        repo.geometryRepository.fetchAndProcessTrail(
                            trailId = trail.id,
                            trailName = trail.name,
                            osmRelationId = relId,
                            onProgress = { p ->
                                if (p.stageMessage != lastStage) {
                                    lastStage = p.stageMessage
                                    log("    ${p.stageMessage}")
                                }
                            }
                        )
                    }.onFailure {
                        log("    error: ${it.javaClass.simpleName}: ${it.message}")
                    }.getOrNull()
                    if (dto != null) break
                    if (attempt < 3) delay(15_000L * attempt)
                }

                if (dto == null) {
                    failed += trail.id
                    log("    FAILED (no geometry returned)")
                    continue
                }

                val walkPoints = dto.segments
                    .filter { it.type == TrailSegmentType.WALK }
                    .flatMap { it.points }
                    .ifEmpty { dto.segments.flatMap { it.points } }
                if (walkPoints.isEmpty()) {
                    failed += trail.id
                    log("    FAILED (geometry had no points)")
                    continue
                }

                val firstPt = walkPoints.first()
                val lastPt = walkPoints.last()
                val nearestTown = queryNearestOsmTown(firstPt.lat, firstPt.lng)
                val tagsMap = runCatching { repo.getRelationOsmTags(trail.id, relId) }
                    .onFailure { log("    tags lookup failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(emptyMap())

                val itemObj = JSONObject().apply {
                    put("id", trail.id)
                    put("osmRelationId", relId)
                    put("computedWalkingKm", dto.totalWalkingKm)
                    put("bboxSouth", walkPoints.minOf { it.lat })
                    put("bboxWest", walkPoints.minOf { it.lng })
                    put("bboxNorth", walkPoints.maxOf { it.lat })
                    put("bboxEast", walkPoints.maxOf { it.lng })
                    put("startLat", firstPt.lat)
                    put("startLng", firstPt.lng)
                    put("endLat", lastPt.lat)
                    put("endLng", lastPt.lng)
                    if (!nearestTown.isNullOrBlank()) put("nearestTown", nearestTown)
                    if (!dto.osmTimestamp.isNullOrBlank()) put("osmTimestamp", dto.osmTimestamp)
                    val tagsJson = JSONObject()
                    tagsMap.forEach { (k, v) -> tagsJson.put(k, v) }
                    put("tags", tagsJson)
                }
                trailsJson.put(itemObj)
                log("    OK: %.1f km, start %.5f,%.5f, town %s".format(
                    java.util.Locale.ROOT, dto.totalWalkingKm, firstPt.lat, firstPt.lng, nearestTown ?: "-"
                ))
            }

            log("")
            log("Written: ${trailsJson.length()} trails")
            log("Failed: ${failed.size} ${failed}")
            log("No relation id: ${skippedNoRelation.size} ${skippedNoRelation}")

            check(trailsJson.length() > 0) {
                "No trails could be fetched from Overpass - see build/catalogue-generator-log.txt"
            }

            val outputRoot = JSONObject().apply {
                put("generatedAt", System.currentTimeMillis() / 1000L)
                put("trails", trailsJson)
            }
            val outFile = resolveCatalogueMetadataOutputFile()
            outFile.parentFile?.mkdirs()
            outFile.writeText(outputRoot.toString(2) + "\n")
            log("Saved to ${outFile.absolutePath}")
        } finally {
            repo.isOnlineOverrideForTests = null
        }
    }
}
