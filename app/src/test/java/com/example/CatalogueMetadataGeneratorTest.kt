package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TrailSegmentType
import com.example.data.remote.OverpassClient
import com.example.util.GeoUtils
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogueMetadataGeneratorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        }.getOrNull() ?: return null
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
        val app = context as DanskStiApp
        val repo = app.repository
        repo.initializePreseededDataIfEmpty()
        val trails = app.database.dao().getAllTrailsList()

        val trailsJson = JSONArray()
        for (trail in trails) {
            val relId = trail.osmRelationId ?: continue
            val dto = repo.geometryRepository.fetchAndProcessTrail(
                trailId = trail.id,
                trailName = trail.name,
                osmRelationId = relId
            ) ?: continue

            val walkPoints = dto.segments
                .filter { it.type == TrailSegmentType.WALK }
                .flatMap { it.points }
                .ifEmpty { dto.segments.flatMap { it.points } }
            if (walkPoints.isEmpty()) continue

            val firstPt = walkPoints.first()
            val lastPt = walkPoints.last()
            val minLat = walkPoints.minOf { it.lat }
            val maxLat = walkPoints.maxOf { it.lat }
            val minLng = walkPoints.minOf { it.lng }
            val maxLng = walkPoints.maxOf { it.lng }
            val nearestTown = queryNearestOsmTown(firstPt.lat, firstPt.lng)
            val tagsMap = repo.getRelationOsmTags(trail.id, relId)

            val itemObj = JSONObject().apply {
                put("id", trail.id)
                put("osmRelationId", relId)
                put("computedWalkingKm", dto.totalWalkingKm)
                put("bboxSouth", minLat)
                put("bboxWest", minLng)
                put("bboxNorth", maxLat)
                put("bboxEast", maxLng)
                put("startLat", firstPt.lat)
                put("startLng", firstPt.lng)
                put("endLat", lastPt.lat)
                put("endLng", lastPt.lng)
                if (!nearestTown.isNullOrBlank()) {
                    put("nearestTown", nearestTown)
                }
                if (!dto.osmTimestamp.isNullOrBlank()) {
                    put("osmTimestamp", dto.osmTimestamp)
                }
                val tagsJson = JSONObject()
                tagsMap.forEach { (k, v) -> tagsJson.put(k, v) }
                put("osmTags", tagsJson)
            }
            trailsJson.put(itemObj)
        }

        check(trailsJson.length() > 0) { "No trails could be fetched from Overpass" }

        val outputRoot = JSONObject().apply {
            put("generatedAt", System.currentTimeMillis() / 1000L)
            put("trails", trailsJson)
        }
        val outFile = resolveCatalogueMetadataOutputFile()
        outFile.parentFile?.mkdirs()
        outFile.writeText(outputRoot.toString(2) + "\n")
    }
}

