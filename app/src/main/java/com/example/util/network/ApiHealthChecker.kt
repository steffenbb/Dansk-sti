package com.example.util.network

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.util.DanskStiTileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

enum class ApiCheckStatus {
    SUCCESS,
    WARNING,
    ERROR,
    UNCONFIGURED
}

data class ApiCheckResult(
    val serviceName: String,
    val endpointUrl: String,
    val status: ApiCheckStatus,
    val httpCode: Int? = null,
    val latencyMs: Long? = null,
    val summary: String,
    val details: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DiagnosticsReport(
    val results: List<ApiCheckResult>,
    val cachedTrailsCount: Int,
    val totalTrailsDiskBytes: Long,
    val generatedAtMs: Long = System.currentTimeMillis()
)

class ApiHealthChecker(
    private val context: Context,
    private val dao: DanskStiDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .applyDebugMainThreadCheck()
        .build()

    suspend fun runFullDiagnostics(): DiagnosticsReport = coroutineScope {
        val dataforsyningenDeferred = async(Dispatchers.IO) { checkDataforsyningen() }
        val overpassDeferred = async(Dispatchers.IO) { checkOverpass() }
        val directOsmDeferred = async(Dispatchers.IO) { checkDirectOsm() }
        val elevationDeferred = async(Dispatchers.IO) { checkElevation() }
        val miljoeportalDeferred = async(Dispatchers.IO) { checkMiljoeportalWfs() }
        val roomDeferred = async(Dispatchers.IO) { checkRoomDatabase() }

        val results = listOf(
            dataforsyningenDeferred.await(),
            overpassDeferred.await(),
            directOsmDeferred.await(),
            elevationDeferred.await(),
            miljoeportalDeferred.await(),
            roomDeferred.await()
        )

        val trailsDir = File(context.filesDir, "trails")
        val cachedFiles = trailsDir.listFiles() ?: emptyArray()
        val totalBytes = cachedFiles.sumOf { it.length() }

        DiagnosticsReport(
            results = results,
            cachedTrailsCount = cachedFiles.size,
            totalTrailsDiskBytes = totalBytes
        )
    }

    suspend fun checkDataforsyningen(): ApiCheckResult = withContext(Dispatchers.IO) {
        val hasKey = DanskStiTileSource.hasValidKey
        val key = DanskStiTileSource.sharedKey

        if (!hasKey) {
            return@withContext ApiCheckResult(
                serviceName = "Dataforsyningen Skærmkortet WMS",
                endpointUrl = "https://api.dataforsyningen.dk/topo_skaermkort_DAF",
                status = ApiCheckStatus.UNCONFIGURED,
                httpCode = null,
                latencyMs = null,
                summary = "API-nøgle mangler (Secrets panel)",
                details = "DATAFORSYNINGEN_API_KEY er ikke indstillet. Kortfliser er midlertidigt deaktiveret for at forhindre fejl."
            )
        }

        // Test EPSG:3857 single tile
        val testUrl = "https://api.dataforsyningen.dk/topo_skaermkort_DAF?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/jpeg&LAYERS=dtk_skaermkort&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=1113194.9,7435883.8,1123235.3,7445924.2&token=$key"
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(testUrl)
                .header("User-Agent", "DanskSti/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                val code = response.code
                if (response.isSuccessful) {
                    ApiCheckResult(
                        serviceName = "Dataforsyningen Skærmkortet WMS",
                        endpointUrl = "api.dataforsyningen.dk",
                        status = ApiCheckStatus.SUCCESS,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "$code OK • Forbindelse aktiv ($elapsed ms)",
                        details = "Kortfliser fra Klimadatastyrelsen hentes korrekt via WMS EPSG:3857 med gyldig nøgle."
                    )
                } else {
                    ApiCheckResult(
                        serviceName = "Dataforsyningen Skærmkortet WMS",
                        endpointUrl = "api.dataforsyningen.dk",
                        status = ApiCheckStatus.ERROR,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "Fejl: HTTP $code ($elapsed ms)",
                        details = if (code == 401 || code == 403) {
                            "API-nøglen blev afvist af Dataforsyningen. Kontroller din token på dataforsyningen.dk."
                        } else {
                            "Dataforsyningen returnerede fejl $code ved hentning af testkortflise."
                        }
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            ApiCheckResult(
                serviceName = "Dataforsyningen Skærmkortet WMS",
                endpointUrl = "api.dataforsyningen.dk",
                status = ApiCheckStatus.ERROR,
                httpCode = null,
                latencyMs = elapsed,
                summary = "Forbindelsesfejl (${e.javaClass.simpleName})",
                details = "Kunne ikke forbinde til api.dataforsyningen.dk: ${e.message}"
            )
        }
    }

    suspend fun checkOverpass(): ApiCheckResult = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter" to "Primær (overpass-api.de)",
            "https://lz4.overpass-api.de/api/interpreter" to "Spejl (lz4.overpass-api.de)",
            "https://overpass.kumi.systems/api/interpreter" to "Spejl (overpass.kumi.systems)"
        )

        val query = "[out:json][timeout:5];out;"
        val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())

        var firstError: Pair<Int?, String>? = null

        for ((url, label) in endpoints) {
            val start = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "DanskSti/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val code = response.code
                    if (response.isSuccessful) {
                        val isMirror = url != "https://overpass-api.de/api/interpreter"
                        return@withContext ApiCheckResult(
                            serviceName = "OpenStreetMap Overpass API",
                            endpointUrl = url,
                            status = if (isMirror) ApiCheckStatus.WARNING else ApiCheckStatus.SUCCESS,
                            httpCode = code,
                            latencyMs = elapsed,
                            summary = "$code OK • $label ($elapsed ms)",
                            details = if (isMirror) {
                                "Primær server var langsom eller utilgængelig. Spejlserver $label svarer og anvendes."
                            } else {
                                "Overpass API svarer lynhurtigt for live rute- og relation-forespørgsler."
                            }
                        )
                    } else {
                        if (firstError == null) firstError = code to "HTTP $code fra $label"
                    }
                }
            } catch (e: Exception) {
                if (firstError == null) firstError = null to "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        ApiCheckResult(
            serviceName = "OpenStreetMap Overpass API",
            endpointUrl = "overpass-api.de",
            status = ApiCheckStatus.ERROR,
            httpCode = firstError?.first,
            latencyMs = null,
            summary = firstError?.second ?: "Forbindelse timeout på alle Overpass spejle",
            details = "Overpass er overbelastet eller utilgængelig. Appen skifter automatisk til direkte OpenStreetMap 0.6 REST API."
        )
    }

    suspend fun checkDirectOsm(): ApiCheckResult = withContext(Dispatchers.IO) {
        val testUrl = "https://api.openstreetmap.org/api/0.6/relation/1202410.json"
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(testUrl)
                .header("User-Agent", "DanskSti/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                val code = response.code
                if (response.isSuccessful) {
                    ApiCheckResult(
                        serviceName = "OpenStreetMap 0.6 Direct API",
                        endpointUrl = "api.openstreetmap.org",
                        status = ApiCheckStatus.SUCCESS,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "$code OK • Direkte OSM fallback klar ($elapsed ms)",
                        details = "OpenStreetMap officielle REST API svarer. Anvendes som hurtig og uafhængig fallback ved overbelastning."
                    )
                } else {
                    ApiCheckResult(
                        serviceName = "OpenStreetMap 0.6 Direct API",
                        endpointUrl = "api.openstreetmap.org",
                        status = ApiCheckStatus.WARNING,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "HTTP $code ($elapsed ms)",
                        details = "OSM relation endpoint returnerede kode $code."
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            ApiCheckResult(
                serviceName = "OpenStreetMap 0.6 Direct API",
                endpointUrl = "api.openstreetmap.org",
                status = ApiCheckStatus.ERROR,
                httpCode = null,
                latencyMs = elapsed,
                summary = "Forbindelsesfejl (${e.javaClass.simpleName})",
                details = "Kunne ikke forbinde til api.openstreetmap.org: ${e.message}"
            )
        }
    }

    suspend fun checkElevation(): ApiCheckResult = withContext(Dispatchers.IO) {
        val testUrl = "https://api.open-meteo.com/v1/elevation?latitude=55.0&longitude=10.0"
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(testUrl)
                .header("User-Agent", "DanskSti/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                val code = response.code
                if (response.isSuccessful) {
                    ApiCheckResult(
                        serviceName = "Open-Meteo Højdedata & Vejr",
                        endpointUrl = "api.open-meteo.com",
                        status = ApiCheckStatus.SUCCESS,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "$code OK • Højdeprofil aktiv ($elapsed ms)",
                        details = "Open-Meteo højdeprofil-tjeneste svarer. Bruges til 100 m resampling af stigningsmeter og højdekurver."
                    )
                } else {
                    ApiCheckResult(
                        serviceName = "Open-Meteo Højdedata & Vejr",
                        endpointUrl = "api.open-meteo.com",
                        status = ApiCheckStatus.WARNING,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "HTTP $code ($elapsed ms)",
                        details = "Open-Meteo returnerede kode $code. Højdeprofil vil forblive skjult ved manglende data."
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            ApiCheckResult(
                serviceName = "Open-Meteo Højdedata & Vejr",
                endpointUrl = "api.open-meteo.com",
                status = ApiCheckStatus.ERROR,
                httpCode = null,
                latencyMs = elapsed,
                summary = "Forbindelsesfejl (${e.javaClass.simpleName})",
                details = "Kunne ikke forbinde til api.open-meteo.com: ${e.message}"
            )
        }
    }

    suspend fun checkMiljoeportalWfs(): ApiCheckResult = withContext(Dispatchers.IO) {
        val testUrl = "https://wfs2-miljoegis.mim.dk/skovdrift/ows?service=WFS&version=2.0.0" +
                "&request=GetFeature&typeNames=skovdrift:Naturstyrelsens+arealoversigt" +
                "&outputFormat=application/json&srsName=EPSG:4326" +
                "&bbox=56.81,9.80,56.85,9.88,urn:ogc:def:crs:EPSG::4326"
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(testUrl)
                .header("User-Agent", "DanskSti/1.8")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - start
                val code = resp.code
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val count = if (body.trimStart().startsWith("{")) {
                        try {
                            org.json.JSONObject(body).optJSONArray("features")?.length() ?: 0
                        } catch (_: Exception) { 0 }
                    } else 0

                    com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.wfsAnswered = true
                    com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.wfsPolygonCount = count
                    com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.lastWfsTimestamp = System.currentTimeMillis()

                    ApiCheckResult(
                        serviceName = "Danmarks Miljøportal WFS",
                        endpointUrl = "wfs2-miljoegis.mim.dk",
                        status = ApiCheckStatus.SUCCESS,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "Svarede (HTTP $code) • $count statspolygoner modtaget ($elapsed ms)",
                        details = "Miljøportal WFS svarede OK og returnerede $count officielle Naturstyrelsen statspolygoner for testområdet. Adgangsklassifikation er fuldt operationel."
                    )
                } else {
                    com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.wfsAnswered = false
                    ApiCheckResult(
                        serviceName = "Danmarks Miljøportal WFS",
                        endpointUrl = "wfs2-miljoegis.mim.dk",
                        status = ApiCheckStatus.WARNING,
                        httpCode = code,
                        latencyMs = elapsed,
                        summary = "Svarede ikke (HTTP $code) • 0 statspolygoner",
                        details = "Serveren svarede med fejlkode $code. Appen benytter lokal Room-cache og OpenStreetMap som fallback."
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.wfsAnswered = false
            com.example.data.repository.AccessLandRepository.Companion.DiagnosticsState.lastError = e.message
            ApiCheckResult(
                serviceName = "Danmarks Miljøportal WFS",
                endpointUrl = "wfs2-miljoegis.mim.dk",
                status = ApiCheckStatus.ERROR,
                httpCode = null,
                latencyMs = elapsed,
                summary = "Svarede ikke • 0 statspolygoner (${e.javaClass.simpleName})",
                details = "Kunne ikke forbinde til Miljøportal WFS: ${e.message}."
            )
        }
    }

    suspend fun checkRoomDatabase(): ApiCheckResult = withContext(Dispatchers.IO) {
        try {
            val shelters = dao.getShelterCount()
            val fri = dao.getFriTeltningCount()
            val pois = dao.getPoiCount()
            val trails = dao.getTrailCount()

            if (shelters > 0 || trails > 0) {
                ApiCheckResult(
                    serviceName = "GeoFA & Lokal Database (Room)",
                    endpointUrl = "Lokal SQLite Room Cache",
                    status = ApiCheckStatus.SUCCESS,
                    httpCode = 200,
                    latencyMs = 2,
                    summary = "$shelters shelters • $fri fri teltning • $pois POIs",
                    details = "$trails vandreruter oprettet i lokal database med $shelters shelters og $fri fri teltningszoner klar til offline brug."
                )
            } else {
                ApiCheckResult(
                    serviceName = "GeoFA & Lokal Database (Room)",
                    endpointUrl = "Lokal SQLite Room Cache",
                    status = ApiCheckStatus.WARNING,
                    httpCode = null,
                    latencyMs = 1,
                    summary = "Database tom (indlæser)",
                    details = "GeoFA overnatningssteder og POIs initialiseres ved første visning."
                )
            }
        } catch (e: Exception) {
            ApiCheckResult(
                serviceName = "GeoFA & Lokal Database (Room)",
                endpointUrl = "Lokal SQLite Room Cache",
                status = ApiCheckStatus.ERROR,
                httpCode = null,
                latencyMs = null,
                summary = "Databasefejl: ${e.message}",
                details = "Fejl ved læsning fra Room databasen: ${e.localizedMessage}"
            )
        }
    }
}
