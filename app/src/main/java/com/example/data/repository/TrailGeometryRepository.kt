package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailGapDto
import com.example.data.model.TrailNameMismatchEntry
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

import com.example.util.network.applyDebugMainThreadCheck

class TrailGeometryRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val geometryAdapter = moshi.adapter(TrailGeometryDto::class.java)
    private val mismatchListType = Types.newParameterizedType(List::class.java, TrailNameMismatchEntry::class.java)
    private val mismatchListAdapter = moshi.adapter<List<TrailNameMismatchEntry>>(mismatchListType)

    companion object {
        private val _globalDownloadProgress = MutableStateFlow<Map<String, TrailDownloadProgress>>(emptyMap())
        val globalDownloadProgress: StateFlow<Map<String, TrailDownloadProgress>> = _globalDownloadProgress.asStateFlow()

        private val inMemoryNameMismatches = CopyOnWriteArrayList<TrailNameMismatchEntry>()

        /**
         * Optional hook for unit tests to supply relation elements and verify progress / name mismatch logging.
         */
        @Volatile
        var customRelationFetcher: ((
            relationId: Long,
            collected: MutableMap<Long, Map<String, Any>>,
            onChildProgress: ((downloaded: Int, total: Int) -> Unit)?
        ) -> Boolean)? = null

        fun updateProgress(progress: TrailDownloadProgress) {
            _globalDownloadProgress.update { current ->
                current + (progress.trailId to progress)
            }
        }

        fun clearProgress(trailId: String) {
            _globalDownloadProgress.update { current ->
                current - trailId
            }
        }

        fun getRecordedNameMismatches(): List<TrailNameMismatchEntry> {
            return inMemoryNameMismatches.toList()
        }

        fun clearRecordedNameMismatches(filesDir: File? = null) {
            inMemoryNameMismatches.clear()
            if (filesDir != null) {
                File(filesDir, "diagnostics_name_mismatches.json").delete()
            }
        }

        fun recordNameMismatchInternal(entry: TrailNameMismatchEntry) {
            inMemoryNameMismatches.removeAll { it.trailId == entry.trailId && it.osmRelationId == entry.osmRelationId }
            inMemoryNameMismatches.add(entry)
        }
    }

    val downloadProgress: StateFlow<Map<String, TrailDownloadProgress>> = globalDownloadProgress

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .applyDebugMainThreadCheck()
        .build()

    private val effectiveFilesDir: File
        get() = try {
            val currentDir = com.example.DanskStiApp.instance.filesDir
            if (currentDir.exists()) currentDir else context.filesDir
        } catch (_: Exception) {
            context.filesDir
        }

    private val trailsDir: File
        get() = File(effectiveFilesDir, "trails").apply { if (!exists()) mkdirs() }

    val routesDir: File
        get() = File(effectiveFilesDir, "routes").apply { if (!exists()) mkdirs() }

    private val mismatchesFile: File
        get() = File(effectiveFilesDir, "diagnostics_name_mismatches.json")

    fun getNameMismatches(): List<TrailNameMismatchEntry> {
        val diskEntries = try {
            if (mismatchesFile.exists()) {
                mismatchListAdapter.fromJson(mismatchesFile.readText()).orEmpty()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val combined = LinkedHashMap<String, TrailNameMismatchEntry>()
        for (e in diskEntries) {
            combined["${e.trailId}_${e.osmRelationId}"] = e
        }
        for (e in inMemoryNameMismatches) {
            combined["${e.trailId}_${e.osmRelationId}"] = e
        }
        return combined.values.sortedByDescending { it.detectedAtEpochMs }
    }

    fun verifyAndRecordRelationNameMismatch(
        trailId: String,
        osmRelationId: Long,
        catalogueName: String,
        osmRelationName: String?
    ): TrailNameMismatchEntry? {
        val actual = osmRelationName?.trim() ?: return null
        val expected = catalogueName.trim()
        if (actual.isEmpty() || expected.isEmpty()) return null
        if (actual == expected) return null

        val entry = TrailNameMismatchEntry(
            trailId = trailId,
            osmRelationId = osmRelationId,
            catalogueName = expected,
            osmRelationName = actual,
            detectedAtEpochMs = System.currentTimeMillis()
        )
        Log.w(
            "Diagnostics",
            "OSM relation name mismatch for $trailId (#$osmRelationId): catalogue=\"$expected\" vs OSM name=\"$actual\""
        )
        recordNameMismatchInternal(entry)
        try {
            val all = getNameMismatches()
            mismatchesFile.writeText(mismatchListAdapter.toJson(all))
        } catch (e: Exception) {
            Log.w("Diagnostics", "Failed to persist name mismatch entry: ${e.message}")
        }
        return entry
    }

    fun getGeometryFile(trailId: String): File {
        val primary = File(trailsDir, "$trailId.json.gz")
        if (primary.exists()) return primary
        val fallback = File(File(context.filesDir, "trails"), "$trailId.json.gz")
        if (fallback.exists()) return fallback
        return primary
    }

    fun getRouteGeometryFile(routeId: String): File {
        val cleanId = routeId.removePrefix("custom_route_").removePrefix("route_")
        val primary = File(routesDir, "$cleanId.json.gz")
        if (primary.exists()) return primary
        val fallback = File(File(context.filesDir, "routes"), "$cleanId.json.gz")
        if (fallback.exists()) return fallback
        return primary
    }

    suspend fun isGeometryCached(trailId: String): Boolean = withContext(Dispatchers.IO) {
        getRouteGeometryFile(trailId).exists() ||
            getGeometryFile(trailId).exists() ||
            File(effectiveFilesDir, "trail_geom_$trailId.json").exists() ||
            File(context.filesDir, "trail_geom_$trailId.json").exists()
    }

    suspend fun loadGeometry(trailId: String): TrailGeometryDto? = withContext(Dispatchers.IO) {
        // 1. Check custom routes directory first
        val routeFile = getRouteGeometryFile(trailId)
        if (routeFile.exists()) {
            try {
                routeFile.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached route geometry for $trailId", e)
            }
        }

        // 2. Check trails directory (.json.gz)
        val file = getGeometryFile(trailId)
        if (file.exists()) {
            try {
                file.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached geometry for $trailId", e)
            }
        }

        // 3. Check plain JSON file in filesDir (e.g. trail_geom_<trailId>.json)
        val plainFile = File(context.filesDir, "trail_geom_$trailId.json")
        if (plainFile.exists()) {
            try {
                val dto = geometryAdapter.fromJson(plainFile.readText())
                if (dto != null) return@withContext dto
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load plain JSON geometry for $trailId", e)
            }
        }

        null
    }

    fun hasWayTagRanges(geometry: TrailGeometryDto): Boolean {
        val walkSegments = geometry.segments.filter { it.type == TrailSegmentType.WALK && it.points.size >= 2 }
        return walkSegments.isNotEmpty() && walkSegments.any { it.wayTagRanges.isNotEmpty() }
    }

    suspend fun saveGeometry(geometry: TrailGeometryDto) = withContext(Dispatchers.IO) {
        if (geometry.segments.all { it.points.isEmpty() }) {
            return@withContext
        }
        val file = getGeometryFile(geometry.trailId)
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tmpFile.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e("TrailGeometry", "Failed to save geometry for ${geometry.trailId}", e)
        }
    }

    suspend fun saveRouteGeometry(routeId: String, geometry: TrailGeometryDto?) = withContext(Dispatchers.IO) {
        if (geometry == null || geometry.segments.all { it.points.isEmpty() }) {
            return@withContext
        }
        val file = getRouteGeometryFile(routeId)
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tmpFile.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e("TrailGeometry", "Failed to save route geometry for $routeId", e)
        }
    }

    suspend fun fetchAndProcessTrail(
        trailId: String,
        trailName: String,
        osmRelationId: Long?,
        onProgress: ((TrailDownloadProgress) -> Unit)? = null
    ): TrailGeometryDto? = withContext(Dispatchers.IO) {
        val emitProg: (Float, Int, Int, Double, String) -> Unit = { frac, dl, tot, km, msg ->
            val p = TrailDownloadProgress(
                trailId = trailId,
                fraction = frac.coerceIn(0f, 1f),
                downloadedRelations = dl,
                totalRelations = tot.coerceAtLeast(1),
                accumulatedKm = km,
                stageMessage = msg
            )
            updateProgress(p)
            onProgress?.invoke(p)
        }

        try {
            emitProg(0.05f, 0, 1, 0.0, "Henter rutedata for $trailName...")
            val relationId = osmRelationId ?: findRelationByName(trailName) ?: return@withContext null
            val rawElements = mutableMapOf<Long, Map<String, Any>>()

            val customFetcher = customRelationFetcher
            if (customFetcher != null) {
                customFetcher.invoke(relationId, rawElements) { dl, tot ->
                    val frac = 0.1f + 0.55f * (dl.toFloat() / tot.coerceAtLeast(1).toFloat())
                    emitProg(frac, dl, tot, 0.0, "Downloader sektion $dl af $tot...")
                }
            } else {
                // 1. Download via direct OSM 0.6 API first (official, canonical, responsive in ~1-2s)
                downloadRelationDirectOsm(relationId, rawElements) { dl, tot ->
                    val frac = 0.1f + 0.55f * (dl.toFloat() / tot.coerceAtLeast(1).toFloat())
                    emitProg(frac, dl, tot, 0.0, "Downloader sektion $dl af $tot...")
                }

                // 2. If direct OSM returned no elements, fall back to Overpass mirrors
                if (rawElements.isEmpty()) {
                    Log.i("TrailGeometry", "Direct OSM API returned no data for relation $relationId ($trailName). Trying Overpass mirrors.")
                    emitProg(0.15f, 0, 1, 0.0, "Henter via Overpass spejlserver...")
                    downloadRelationRecursive(relationId, rawElements)
                }
            }

            if (rawElements.isEmpty()) {
                Log.e("TrailGeometry", "No elements downloaded for relation $relationId ($trailName) from any source")
                return@withContext null
            }

            emitProg(0.70f, rawElements.size, rawElements.size.coerceAtLeast(1), 0.0, "Sammensætter rutegeometri...")
            val dto = processRelationData(trailId, relationId, rawElements) { frac, km, msg ->
                emitProg(frac, rawElements.size, rawElements.size.coerceAtLeast(1), km, msg)
            }

            // Round 9 C Requirement 1: On first download, compare the relation's name tag with the catalogue name
            // and log any mismatch in Diagnostics.
            verifyAndRecordRelationNameMismatch(
                trailId = trailId,
                osmRelationId = relationId,
                catalogueName = trailName,
                osmRelationName = dto.osmRelationName
            )

            emitProg(
                1.0f,
                rawElements.size,
                rawElements.size.coerceAtLeast(1),
                dto.totalWalkingKm,
                "Færdig (${dto.totalWalkingKm} km)"
            )
            dto
        } finally {
            clearProgress(trailId)
        }
    }

    private fun findRelationByName(name: String): Long? {
        val cleanName = name.replace(Regex("\\s*\\(.*\\)"), "").trim()
        val query = """
            [out:json][timeout:10];
            relation["type"="route"]["route"~"hiking|foot"]["name"~"$cleanName",i](54.5,7.5,58.0,15.5);
            out tags bb;
        """.trimIndent()

        val mirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in mirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val jsonStr = response.body?.string() ?: return@use
                    val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                    @Suppress("UNCHECKED_CAST")
                    val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()

                    Log.i("TrailGeometry", "=== Overpass search matches for \"$name\" (${elements.size} found on $url) ===")
                    for (el in elements) {
                        val id = (el["id"] as? Number)?.toLong()
                        @Suppress("UNCHECKED_CAST")
                        val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                        val matchName = tags["name"] ?: ""
                        val type = tags["type"] ?: ""
                        val route = tags["route"] ?: ""
                        val dist = tags["distance"] ?: ""
                        Log.i("TrailGeometry", "  ID: $id, name: \"$matchName\", type: \"$type\", route: \"$route\", dist: \"$dist\"")
                    }
                    if (elements.size == 1) {
                        return (elements[0]["id"] as? Number)?.toLong()
                    } else {
                        if (elements.size > 1) {
                            Log.w("TrailGeometry", "Multiple relation matches (${elements.size}) for \"$name\". Returning null.")
                        }
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Error searching relation for $name on $url: ${e.message}")
            }
        }
        return null
    }

    private fun downloadRelationRecursive(relationId: Long, collected: MutableMap<Long, Map<String, Any>>) {
        if (collected.containsKey(relationId)) return
        val query = """
            [out:json][timeout:60];
            relation($relationId);
            out geom;
        """.trimIndent()

        val overpassMirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in overpassMirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string() ?: return@use
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                        if (elements.isNotEmpty()) {
                            // Requirement 22: After downloading a relation, fetch its member way tags (way(r); out tags;)
                            // so ways with route=ferry are marked as FERRY segments.
                            val wayTagsMap = fetchMemberWayTags(relationId)
                            for (el in elements) {
                                val id = (el["id"] as? Number)?.toLong()
                                val type = el["type"] as? String
                                if (id != null && type == "relation") {
                                    val mutableEl = el.toMutableMap()
                                    @Suppress("UNCHECKED_CAST")
                                    val members = el["members"] as? List<Map<String, Any>> ?: emptyList()
                                    val enrichedMembers = members.map { m ->
                                        val mType = m["type"] as? String
                                        val mRef = (m["ref"] as? Number)?.toLong()
                                        if (mType == "way" && mRef != null) {
                                            val wTags = wayTagsMap[mRef]
                                            if (wTags != null) {
                                                val mCopy = m.toMutableMap()
                                                mCopy["tags"] = wTags
                                                mCopy
                                            } else m
                                        } else m
                                    }
                                    mutableEl["members"] = enrichedMembers
                                    collected[id] = mutableEl
                                    for (m in enrichedMembers) {
                                        val mType = m["type"] as? String
                                        val mRef = (m["ref"] as? Number)?.toLong()
                                        if (mType == "relation" && mRef != null) {
                                            downloadRelationRecursive(mRef, collected)
                                        }
                                    }
                                }
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Overpass mirror $url failed for relation $relationId: ${e.message}")
            }
        }
    }

    /**
     * Requirement 22: Fetches member way tags for a relation using `relation($relationId); way(r); out tags;`
     * so ways with `route=ferry` are detected and marked as FERRY segments.
     */
    private fun fetchMemberWayTags(relationId: Long): Map<Long, Map<String, String>> {
        val query = """
            [out:json][timeout:30];
            relation($relationId);
            way(r);
            out tags;
        """.trimIndent()

        val overpassMirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in overpassMirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string() ?: return@use
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                        val result = mutableMapOf<Long, Map<String, String>>()
                        for (el in elements) {
                            val id = (el["id"] as? Number)?.toLong() ?: continue
                            val type = el["type"] as? String
                            if (type == "way") {
                                @Suppress("UNCHECKED_CAST")
                                val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                                result[id] = tags
                            }
                        }
                        return result
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Way tags fetch failed on $url for relation $relationId: ${e.message}")
            }
        }
        return emptyMap()
    }

    /**
     * Requirement 13: Look up elevation for both ends of a STRAIGHT leg with Open-Meteo;
     * if that fails, return null. Never 15.0.
     */
    suspend fun fetchEndpointElevations(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/elevation?latitude=$fromLat,$toLat&longitude=$fromLng,$toLng"
        try {
            val request = Request.Builder().url(url).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (!jsonStr.isNullOrEmpty()) {
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                        @Suppress("UNCHECKED_CAST")
                        val eleList = root?.get("elevation") as? List<Number?>
                        if (eleList != null && eleList.size >= 2) {
                            return@withContext Pair(eleList[0]?.toDouble(), eleList[1]?.toDouble())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TrailGeometry", "STRAIGHT leg endpoint elevation lookup failed: ${e.message}")
        }
        Pair(null, null)
    }

    private fun isLikelyDanishChildRelation(rel: Map<String, Any>?): Boolean {
        if (rel == null) return true
        @Suppress("UNCHECKED_CAST")
        val tags = rel["tags"] as? Map<String, String> ?: return true
        val country = (tags["country"] ?: tags["ISO3166-1"] ?: tags["addr:country"] ?: "").uppercase()
        if (country.isNotEmpty()) {
            return country.contains("DK") || country.contains("DENMARK") || country.contains("DANMARK")
        }
        val combinedText = listOfNotNull(
            tags["name"],
            tags["name:da"],
            tags["name:en"],
            tags["description"],
            tags["from"],
            tags["to"],
            tags["section"],
            tags["operator"]
        ).joinToString(" ").lowercase()
        if (combinedText.isBlank()) return true

        val foreignMarkers = listOf(
            "deutschland", "germany", "sverige", "sweden", "norge", "norway",
            "italia", "italy", "schweiz", "switzerland", "österreich", "austria",
            "slovenija", "greece", "suomi", "finland", "schleswig-holstein",
            "niedersachsen", "hessen", "baden-württemberg", "bayern", "skåne",
            "halland", "bohuslän", "flensburg -", "hamburg", "frankfurt"
        )
        val danishMarkers = listOf(
            "danmark", "denmark", "dansk", "jylland", "sjælland", "fyn",
            "hærvejen", "gendarmstien", "mols", "kruså", "padborg", "grænse",
            "viborg", "silkeborg", "aarhus", "århus", "grenaa", "frederikshavn",
            "hirtshals", "skagen", "københavn", "helsingør", "korsør", "nyborg",
            "svendborg", "sønderborg", "odense", "roskilde", "køge"
        )
        if (danishMarkers.any { combinedText.contains(it) }) return true
        if (foreignMarkers.any { combinedText.contains(it) }) return false
        return true
    }

    private fun downloadRelationDirectOsm(
        relationId: Long,
        collected: MutableMap<Long, Map<String, Any>>,
        onChildProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ) {
        if (collected.containsKey(relationId)) return
        val url = "https://api.openstreetmap.org/api/0.6/relation/$relationId/full.json"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("TrailGeometry", "Direct OSM API returned ${response.code} for relation $relationId")
                    return
                }
                val jsonStr = response.body?.string() ?: return
                @Suppress("UNCHECKED_CAST")
                val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()

                val nodeMap = mutableMapOf<Long, Map<String, Double>>()
                val wayMap = mutableMapOf<Long, MutableMap<String, Any>>()
                val relMap = mutableMapOf<Long, MutableMap<String, Any>>()

                for (el in elements) {
                    val id = (el["id"] as? Number)?.toLong() ?: continue
                    val type = el["type"] as? String
                    when (type) {
                        "node" -> {
                            val lat = (el["lat"] as? Number)?.toDouble() ?: 0.0
                            val lon = (el["lon"] as? Number)?.toDouble() ?: 0.0
                            nodeMap[id] = mapOf("lat" to lat, "lon" to lon)
                        }
                        "way" -> {
                            wayMap[id] = el.toMutableMap()
                        }
                        "relation" -> {
                            relMap[id] = el.toMutableMap()
                        }
                    }
                }

                // Resolve way node coordinates into inline geometry
                for ((_, way) in wayMap) {
                    @Suppress("UNCHECKED_CAST")
                    val nodeRefs = (way["nodes"] as? List<Number>)?.map { it.toLong() } ?: emptyList()
                    val wayGeom = nodeRefs.mapNotNull { nodeMap[it] }
                    way["geometry"] = wayGeom
                }

                val currentRel = relMap[relationId] ?: return
                @Suppress("UNCHECKED_CAST")
                val rawMembers = currentRel["members"] as? List<Map<String, Any>> ?: emptyList()
                val resolvedMembers = mutableListOf<Map<String, Any>>()
                val allChildRelIds = mutableListOf<Long>()

                for (m in rawMembers) {
                    val mType = m["type"] as? String
                    val mRef = (m["ref"] as? Number)?.toLong()
                    if (mType == "way" && mRef != null) {
                        val fullWay = wayMap[mRef]
                        val memberCopy = m.toMutableMap()
                        if (fullWay != null) {
                            @Suppress("UNCHECKED_CAST")
                            val geom = fullWay["geometry"] as? List<Map<String, Double>> ?: emptyList()
                            memberCopy["geometry"] = geom
                            @Suppress("UNCHECKED_CAST")
                            val tags = fullWay["tags"] as? Map<String, String> ?: emptyMap()
                            memberCopy["tags"] = tags
                        }
                        resolvedMembers.add(memberCopy)
                    } else if (mType == "relation" && mRef != null) {
                        resolvedMembers.add(m)
                        allChildRelIds.add(mRef)
                    } else {
                        resolvedMembers.add(m)
                    }
                }

                currentRel["members"] = resolvedMembers
                collected[relationId] = currentRel

                // Filter out non-Danish child relations if this is an international super-relation (e.g., E1, E6)
                val filteredChildIds = if (allChildRelIds.size > 1) {
                    val danishCandidates = allChildRelIds.filter { cid -> isLikelyDanishChildRelation(relMap[cid]) }
                    if (danishCandidates.isNotEmpty()) danishCandidates else allChildRelIds
                } else {
                    allChildRelIds
                }

                // Recursively fetch child relations with progress updates
                for ((idx, cid) in filteredChildIds.withIndex()) {
                    onChildProgress?.invoke(idx + 1, filteredChildIds.size)
                    downloadRelationDirectOsm(cid, collected, null)
                }
            }
        } catch (e: Exception) {
            Log.e("TrailGeometry", "Error fetching direct OSM relation $relationId: ${e.message}", e)
        }
    }

    private fun collectWayMembersRecursive(
        relId: Long,
        elements: Map<Long, Map<String, Any>>,
        visited: MutableSet<Long>,
        outWays: MutableList<Map<String, Any>>
    ) {
        if (!visited.add(relId)) return
        val rel = elements[relId] ?: return
        @Suppress("UNCHECKED_CAST")
        val members = rel["members"] as? List<Map<String, Any>> ?: emptyList()
        for (m in members) {
            val mType = m["type"] as? String
            val mRef = (m["ref"] as? Number)?.toLong()
            if (mType == "way") {
                outWays.add(m)
            } else if (mType == "relation" && mRef != null) {
                collectWayMembersRecursive(mRef, elements, visited, outWays)
            }
        }
    }

    private fun isPointInDenmarkBbox(lat: Double, lng: Double): Boolean {
        return lat in 54.40..58.10 && lng in 7.50..15.60
    }

    private fun processRelationData(
        trailId: String,
        rootRelationId: Long,
        elements: Map<Long, Map<String, Any>>,
        onProgress: ((fraction: Float, accumulatedKm: Double, message: String) -> Unit)? = null
    ): TrailGeometryDto {
        val rootEl = elements[rootRelationId]
        @Suppress("UNCHECKED_CAST")
        val rootTags = rootEl?.get("tags") as? Map<String, String> ?: emptyMap()
        val osmRelationName = rootTags["name"]

        val orderedMembers = mutableListOf<Map<String, Any>>()
        collectWayMembersRecursive(
            relId = rootRelationId,
            elements = elements,
            visited = mutableSetOf(),
            outWays = orderedMembers
        )

        val rawWalkWays = mutableListOf<WayData>()
        val rawSideBranchWays = mutableListOf<WayData>()

        for (m in orderedMembers) {
            val mType = m["type"] as? String
            if (mType != "way") continue
            @Suppress("UNCHECKED_CAST")
            val geom = m["geometry"] as? List<Map<String, Double>> ?: continue
            if (geom.size < 2) continue

            val pts = geom.map { GeoPointDto(lat = it["lat"] ?: 0.0, lng = it["lon"] ?: 0.0) }
            val role = m["role"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val tags = m["tags"] as? Map<String, String> ?: emptyMap()

            val isFerry = (role == "ferry") || (tags["route"] == "ferry") || (tags["highway"] == "ferry")
            val segType = if (isFerry) TrailSegmentType.FERRY else TrailSegmentType.WALK

            val wayData = WayData(
                type = segType,
                points = pts,
                role = role,
                highway = tags["highway"],
                surface = tags["surface"]
            )
            if (role == "alternative" || role == "excursion") {
                rawSideBranchWays.add(wayData)
            } else {
                rawWalkWays.add(wayData)
            }
        }

        // If an international relation (e.g. E1, E6) contains both Danish and non-Danish ways,
        // keep the Danish ways so the Danish route geometry and region are accurate.
        val hasDanishWalkWays = rawWalkWays.any { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        val walkWays = if (hasDanishWalkWays) {
            rawWalkWays.filter { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        } else {
            rawWalkWays
        }
        val sideBranchWays = if (hasDanishWalkWays) {
            rawSideBranchWays.filter { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        } else {
            rawSideBranchWays
        }

        val (mainSegments, gaps) = stitchWays(walkWays)
        val (sideSegments, _) = stitchWays(sideBranchWays)

        // Simplify with Douglas-Peucker at 4m and scale wayTagRanges proportionally if total segment length changes
        val simplifiedMain = mainSegments.map { seg ->
            val simpPts = douglasPeucker(seg.points, 4.0).toMutableList()
            val oldKm = seg.wayTagRanges.lastOrNull()?.toKm ?: 0.0
            val newKm = calculateSegmentKm(simpPts)
            val scale = if (oldKm > 1e-6 && newKm > 0.0) newKm / oldKm else 1.0
            val scaledRanges = seg.wayTagRanges.map { r ->
                r.copy(fromKm = r.fromKm * scale, toKm = r.toKm * scale)
            }
            InternalSegment(seg.type, simpPts, seg.highway, seg.surface, scaledRanges)
        }
        val simplifiedSide = sideSegments.map { seg ->
            val simpPts = douglasPeucker(seg.points, 4.0).toMutableList()
            val oldKm = seg.wayTagRanges.lastOrNull()?.toKm ?: 0.0
            val newKm = calculateSegmentKm(simpPts)
            val scale = if (oldKm > 1e-6 && newKm > 0.0) newKm / oldKm else 1.0
            val scaledRanges = seg.wayTagRanges.map { r ->
                r.copy(fromKm = r.fromKm * scale, toKm = r.toKm * scale)
            }
            InternalSegment(seg.type, simpPts, seg.highway, seg.surface, scaledRanges)
        }

        val prelimTotalKm = simplifiedMain
            .filter { it.type == TrailSegmentType.WALK }
            .sumOf { calculateSegmentKm(it.points) }
        onProgress?.invoke(0.82f, roundTo1Decimal(prelimTotalKm), "Henter højdeprofil (${roundTo1Decimal(prelimTotalKm)} km)...")

        // Keep Douglas-Peucker (4m) points as route geometry; resample for elevation lookup
        val finalSegments = applyElevationToSegments(simplifiedMain)
        val finalSideBranches = applyElevationToSegments(simplifiedSide)

        var totalWalkingKm = 0.0
        var totalAscent = 0.0
        var totalDescent = 0.0
        var hasElevationData = false
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (seg in finalSegments) {
            if (seg.type == TrailSegmentType.WALK) {
                totalWalkingKm += seg.lengthKm
                val pts = seg.points
                for (i in 0 until pts.size - 1) {
                    val e1 = pts[i].ele
                    val e2 = pts[i + 1].ele
                    if (e1 != null && e2 != null) {
                        hasElevationData = true
                        val diff = e2 - e1
                        if (diff > 3.0) {
                            totalAscent += diff
                        } else if (diff < -3.0) {
                            totalDescent += -diff
                        }
                    }
                }
            }
            for (p in seg.points) {
                minLat = min(minLat, p.lat)
                maxLat = max(maxLat, p.lat)
                minLng = min(minLng, p.lng)
                maxLng = max(maxLng, p.lng)
            }
        }

        val gapsOver200mCount = gaps.count { it.gapMeters > 200.0 }
        val osmTimestamp = (rootEl?.get("timestamp") as? String)

        return TrailGeometryDto(
            trailId = trailId,
            osmRelationId = rootRelationId,
            segments = finalSegments,
            sideBranches = finalSideBranches,
            gaps = gaps,
            totalWalkingKm = roundTo1Decimal(totalWalkingKm),
            totalAscentM = if (hasElevationData) totalAscent.toInt() else null,
            totalDescentM = if (hasElevationData) totalDescent.toInt() else null,
            gapsOver200mCount = gapsOver200mCount,
            bboxNorth = if (maxLat > -90.0) maxLat else 0.0,
            bboxSouth = if (minLat < 90.0) minLat else 0.0,
            bboxEast = if (maxLng > -180.0) maxLng else 0.0,
            bboxWest = if (minLng < 180.0) minLng else 0.0,
            osmTimestamp = osmTimestamp,
            osmRelationName = osmRelationName
        )
    }

    private data class WayData(
        val type: TrailSegmentType,
        val points: List<GeoPointDto>,
        val role: String,
        val highway: String? = null,
        val surface: String? = null
    )

    private data class InternalSegment(
        val type: TrailSegmentType,
        val points: MutableList<GeoPointDto>,
        val highway: String? = null,
        val surface: String? = null,
        val wayTagRanges: List<com.example.data.model.WayTagRangeDto> = emptyList()
    )

    private fun stitchWays(ways: List<WayData>): Pair<List<InternalSegment>, List<TrailGapDto>> {
        val segments = mutableListOf<InternalSegment>()
        val gaps = mutableListOf<TrailGapDto>()
        var curPts = mutableListOf<GeoPointDto>()
        var curType = TrailSegmentType.WALK
        var curHighway: String? = null
        var curSurface: String? = null
        var curRanges = mutableListOf<com.example.data.model.WayTagRangeDto>()
        var curKm = 0.0

        for (w in ways) {
            if (curPts.isEmpty()) {
                val wKm = calculateSegmentKm(w.points)
                curPts.addAll(w.points)
                curType = w.type
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface
                    )
                )
                curKm = wKm
                continue
            }

            if (w.type != curType) {
                segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
                val wKm = calculateSegmentKm(w.points)
                curPts = w.points.toMutableList()
                curType = w.type
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface
                    )
                )
                curKm = wKm
                continue
            }

            val last = curPts.last()
            val firstP = w.points.first()
            val lastP = w.points.last()

            val dNorm = distanceMeters(last, firstP)
            val dFlip = distanceMeters(last, lastP)

            val oriented = if (dFlip < dNorm) w.points.reversed() else w.points
            val gap = min(dNorm, dFlip)

            if (gap > 30.0) {
                segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
                gaps.add(TrailGapDto(fromPoint = last, toPoint = oriented.first(), gapMeters = gap))
                val wKm = calculateSegmentKm(oriented)
                curPts = oriented.toMutableList()
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface
                    )
                )
                curKm = wKm
            } else {
                if (curHighway == null && w.highway != null) curHighway = w.highway
                if (curSurface == null && w.surface != null) curSurface = w.surface
                val addedPts = if (gap < 1.0) oriented.drop(1) else oriented
                var addedMeters = 0.0
                var prev = last
                for (p in addedPts) {
                    addedMeters += distanceMeters(prev, p)
                    prev = p
                }
                curPts.addAll(addedPts)
                val startKm = curKm
                val endKm = startKm + (addedMeters / 1000.0)
                curRanges.add(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = startKm,
                        toKm = endKm,
                        highway = w.highway,
                        surface = w.surface
                    )
                )
                curKm = endKm
            }
        }

        if (curPts.isNotEmpty()) {
            segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
        }

        return Pair(segments, gaps)
    }

    /**
     * Preserves Douglas-Peucker (4m) points as the real route geometry.
     * Uses 100m resampling ONLY to query elevation from Open-Meteo,
     * then interpolates elevation onto the real points.
     * If elevation lookup fails, stores null and never pads with a value.
     */
    private fun applyElevationToSegments(segments: List<InternalSegment>): List<TrailSegmentDto> {
        val resultSegments = mutableListOf<TrailSegmentDto>()

        for (seg in segments) {
            val realPts = seg.points
            if (realPts.isEmpty()) continue

            val km = calculateSegmentKm(realPts)

            if (seg.type == TrailSegmentType.FERRY || realPts.size < 2) {
                resultSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                        lengthKm = km,
                        highway = seg.highway,
                        surface = seg.surface,
                        wayTagRanges = seg.wayTagRanges
                    )
                )
                continue
            }

            if (customRelationFetcher != null) {
                resultSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, it.ele) },
                        lengthKm = km,
                        highway = seg.highway,
                        surface = seg.surface,
                        wayTagRanges = seg.wayTagRanges
                    )
                )
                continue
            }

            // 1. Resample every 100m (or adaptive interval for long routes >25 km so we cap at ~250 samples) ONLY to look up elevation
            val resampled = mutableListOf<GeoPointDto>()
            val resampledDistances = mutableListOf<Double>()
            resampled.add(realPts.first())
            resampledDistances.add(0.0)

            var distFromLastSample = 0.0
            var totalDist = 0.0
            val targetInterval = max(100.0, (km * 1000.0) / 250.0)

            for (i in 0 until realPts.size - 1) {
                val p1 = realPts[i]
                val p2 = realPts[i + 1]
                val segLen = distanceMeters(p1, p2)
                if (segLen <= 0.0) continue

                var segPos = 0.0
                while (distFromLastSample + (segLen - segPos) >= targetInterval) {
                    val needed = targetInterval - distFromLastSample
                    segPos += needed
                    val ratio = (segPos / segLen).coerceIn(0.0, 1.0)
                    val interpLat = p1.lat + ratio * (p2.lat - p1.lat)
                    val interpLng = p1.lng + ratio * (p2.lng - p1.lng)
                    totalDist += needed
                    resampled.add(GeoPointDto(interpLat, interpLng, null))
                    resampledDistances.add(totalDist)
                    distFromLastSample = 0.0
                }
                val remaining = segLen - segPos
                distFromLastSample += remaining
                totalDist += remaining
            }

            if (resampled.size < 2 || resampled.last() != realPts.last()) {
                resampled.add(realPts.last())
                resampledDistances.add(totalDist)
            }

            // 2. Query Open-Meteo for resampled coordinates
            val elevations = mutableListOf<Double>()
            var lookupFailed = false

            for (chunk in resampled.chunked(100)) {
                val lats = chunk.joinToString(",") { it.lat.toString() }
                val lons = chunk.joinToString(",") { it.lng.toString() }
                val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
                try {
                    val request = Request.Builder().url(url).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string()
                            if (!jsonStr.isNullOrEmpty()) {
                                val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                                @Suppress("UNCHECKED_CAST")
                                val eleList = root?.get("elevation") as? List<Number>
                                if (eleList != null && eleList.size == chunk.size) {
                                    for (e in eleList) {
                                        elevations.add(e.toDouble())
                                    }
                                } else {
                                    lookupFailed = true
                                }
                            } else {
                                lookupFailed = true
                            }
                        } else {
                            lookupFailed = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TrailGeometry", "Elevation fetch failed: ${e.message}")
                    lookupFailed = true
                }
                if (lookupFailed) break
            }

            if (lookupFailed || elevations.size != resampled.size) {
                // If elevation lookup fails, store null and never pad with a value
                resultSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                        lengthKm = km,
                        highway = seg.highway,
                        surface = seg.surface,
                        wayTagRanges = seg.wayTagRanges
                    )
                )
                continue
            }

            // 5-point moving average smoothing on resampled elevations
            val smoothedElevations = mutableListOf<Double>()
            val windowSize = 5
            for (i in elevations.indices) {
                val start = max(0, i - windowSize / 2)
                val end = min(elevations.size - 1, i + windowSize / 2)
                val avg = elevations.subList(start, end + 1).average()
                smoothedElevations.add(avg)
            }

            // 3. Interpolate elevation onto the real Douglas-Peucker points
            val realDistances = DoubleArray(realPts.size)
            var curDist = 0.0
            realDistances[0] = 0.0
            for (i in 0 until realPts.size - 1) {
                curDist += distanceMeters(realPts[i], realPts[i + 1])
                realDistances[i + 1] = curDist
            }

            var rIdx = 0
            val finalRealPoints = realPts.mapIndexed { idx, pt ->
                val d = realDistances[idx]
                while (rIdx < resampledDistances.size - 2 && resampledDistances[rIdx + 1] < d) {
                    rIdx++
                }
                val d0 = resampledDistances[rIdx]
                val d1 = resampledDistances[rIdx + 1]
                val span = d1 - d0
                val ratio = if (span > 0.0) ((d - d0) / span).coerceIn(0.0, 1.0) else 0.0
                val ele = if (smoothedElevations.isNotEmpty()) {
                    val e0 = smoothedElevations.getOrElse(rIdx) { smoothedElevations.last() }
                    val e1 = smoothedElevations.getOrElse(rIdx + 1) { e0 }
                    e0 + ratio * (e1 - e0)
                } else null
                GeoPointDto(pt.lat, pt.lng, ele)
            }

            resultSegments.add(
                TrailSegmentDto(
                    type = seg.type,
                    points = finalRealPoints,
                    lengthKm = km,
                    highway = seg.highway,
                    surface = seg.surface,
                    wayTagRanges = seg.wayTagRanges
                )
            )
        }

        return resultSegments
    }

    private fun douglasPeucker(points: List<GeoPointDto>, epsilon: Double): List<GeoPointDto> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true

        val stack = java.util.ArrayDeque<Pair<Int, Int>>()
        stack.push(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (startIndex, endIndex) = stack.pop()
            if (endIndex <= startIndex + 1) continue

            val a = points[startIndex]
            val b = points[endIndex]
            val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
            val dy = (b.lat - a.lat) * 110540.0
            val denom = dx * dx + dy * dy

            var dMax = 0.0
            var maxIdx = startIndex

            for (i in startIndex + 1 until endIndex) {
                val p = points[i]
                val d = if (denom == 0.0) {
                    distanceMeters(p, a)
                } else {
                    val x = (p.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
                    val y = (p.lat - a.lat) * 110540.0
                    val t = max(0.0, min(1.0, (x * dx + y * dy) / denom))
                    val projLon = a.lng + t * (b.lng - a.lng)
                    val projLat = a.lat + t * (b.lat - a.lat)
                    distanceMeters(p.lat, p.lng, projLat, projLon)
                }
                if (d > dMax) {
                    maxIdx = i
                    dMax = d
                }
            }

            if (dMax > epsilon) {
                keep[maxIdx] = true
                stack.push(startIndex to maxIdx)
                stack.push(maxIdx to endIndex)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun distanceMeters(p1: GeoPointDto, p2: GeoPointDto): Double {
        return distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    private fun calculateSegmentKm(points: List<GeoPointDto>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total / 1000.0
    }

    private fun roundTo1Decimal(value: Double): Double {
        return Math.round(value * 10.0) / 10.0
    }
}
