import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class CatalogueInput(val id: String, val name: String, val osmRelationId: Long?)

val catalogueInputs = listOf(
    CatalogueInput("trail_ohavsstien", "Øhavsstien", 1202410L),
    CatalogueInput("trail_haervejen", "Hærvejen (Jylland)", 1792585L),
    CatalogueInput("trail_camoenoen", "Camønoen (Kongerigets venligste vandrerute)", null),
    CatalogueInput("trail_bornholm_kyststi", "Kyststien Bornholm", null),
    CatalogueInput("trail_gendarmstien", "Gendarmstien", 443124L),
    CatalogueInput("trail_himmerlandsstien", "Himmerlandsstien", 3031529L),
    CatalogueInput("trail_amarminoen", "Amarminoen", null),
    CatalogueInput("trail_mols_bjerge", "Molsruten", 178382L),
    CatalogueInput("trail_traekstien", "Trækstien Gudenåen", 1710241L),
    CatalogueInput("trail_skjoldungestien", "Skjoldungestien", 1458788L),
    CatalogueInput("trail_drivvejen", "Drivvejen (Vestkystens handelsrute)", 1166772L),
    CatalogueInput("trail_nordsjaelland", "Nordsjællandsruten", 3481232L),
    CatalogueInput("trail_e1", "E1", 297398L),
    CatalogueInput("trail_e6", "E6", 556666L),
    CatalogueInput("trail_nordsoestien", "Nordsøstien", 2203180L),
    CatalogueInput("trail_sjaellandsleden", "Sjællandsleden", 1970035L),
    CatalogueInput("trail_pilgrimsruten", "Pilgrimsruten", 1792586L),
    CatalogueInput("trail_fjordstien", "Fjordstien", 1595942L),
    CatalogueInput("trail_sjaellandsleden_2012", "Sjællandsleden 2012", 2813821L),
    CatalogueInput("trail_sydsjaellandsleden", "Sydsjællandsleden", 309907L),
    CatalogueInput("trail_haervejen_nordvest", "Hærvejen nordvest", 4004055L),
    CatalogueInput("trail_moen_rundt", "Møn Rundt", 2839163L),
    CatalogueInput("trail_haervejen_nordoest", "Hærvejen nordøst", 4004054L),
    CatalogueInput("trail_kyst_til_kyst_stien", "Kyst til Kyst stien", 374893L),
    CatalogueInput("trail_camino_haderslev_naes", "Camino Haderslev Næs", 8220396L),
    CatalogueInput("trail_vestkyststien", "Vestkyststien", 1166751L),
    CatalogueInput("trail_paradisruten", "Paradisruten", 2836954L),
    CatalogueInput("trail_sejeroe_bugt_ruten", "Sejerø Bugt Ruten", 2838931L),
    CatalogueInput("trail_lagunestien", "Lagunestien", 2820402L),
    CatalogueInput("trail_gudernes_straede", "Gudernes Stræde", 2717313L),
    CatalogueInput("trail_kongeaastien", "Kongeåstien", 2021716L),
    CatalogueInput("trail_aarhus_silkeborg", "Vandreruten Århus-Silkeborg", 3029875L),
    CatalogueInput("trail_alsstien", "Alsstien", 1727731L),
    CatalogueInput("trail_haervejen_nord", "Hærvejen nord", 4003809L),
    CatalogueInput("trail_bryrupbanestien", "Bryrupbanestien", 3029770L),
    CatalogueInput("trail_jyderupstien", "Jyderupstien", 2076253L),
    CatalogueInput("trail_musholmleden", "Musholmleden", 2838766L),
    CatalogueInput("trail_nibe_hvalpsund", "Naturstien Nibe-Hvalpsund", 3030656L),
    CatalogueInput("trail_marskstien", "Marskstien", 8397167L),
    CatalogueInput("trail_halsninoen", "Halsninoen", 13577498L),
    CatalogueInput("trail_alhedestien", "Alhedestien", 3031593L),
    CatalogueInput("trail_gjernstien", "Gjernstien", 1710329L),
    CatalogueInput("trail_trelleborgleden", "Trelleborgleden", 2843842L),
    CatalogueInput("trail_moelleaadalen", "Mølleådalen", 3038370L),
    CatalogueInput("trail_den_skaeve_bane", "Den Skæve Bane", 3030189L),
    CatalogueInput("trail_gjerrildbanestien", "Gjerrildbanestien", 2932602L),
    CatalogueInput("trail_fjord_til_fjord_naturstien", "Fjord til Fjord Naturstien", 2466789L),
    CatalogueInput("trail_susaaleden", "Susåleden", 2830942L),
    CatalogueInput("trail_falster_rundt", "Falster Rundt", 2835117L),
    CatalogueInput("trail_oestersoestien", "Østersøstien", 2836839L),
    CatalogueInput("trail_djurslandstien", "Djurslandstien", 14190349L)
)

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

fun classifyRegion(centerLat: Double, centerLng: Double): String {
    return when {
        centerLng >= 14.5 -> "Bornholm"
        centerLat >= 56.5 && centerLng < 11.2 -> "Nordjylland"
        centerLat >= 55.65 && centerLng < 11.0 -> "Midtjylland"
        centerLat < 55.65 && centerLng < 10.0 -> "Syd- og Sønderjylland"
        centerLng in 9.6..11.0 && centerLat in 54.7..55.65 -> "Fyn og Øhavet"
        centerLat >= 55.75 && centerLng >= 11.8 -> "Nordsjælland og Hovedstaden"
        centerLat < 55.15 && centerLng in 11.0..12.6 -> "Lolland, Falster og Møn"
        else -> "Sjælland"
    }
}

fun queryOverpass(query: String): String? {
    val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    for (endpoint in endpoints) {
        try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "DanskSti/1.0 (Android; https://dansksti.example.com)")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            System.err.println("Overpass query failed on $endpoint: ${e.message}")
        }
    }
    return null
}

fun main() {
    val relationIds = catalogueInputs.mapNotNull { it.osmRelationId }
    val idCsv = relationIds.joinToString(",")
    val query = "[out:json][timeout:60];relation(id:$idCsv);out tags bb geom;"
    println("Querying Overpass for ${relationIds.size} relations...")
    val responseText = queryOverpass(query)
    val resultsArray = JSONArray()
    val generatedAt = System.currentTimeMillis()

    if (responseText != null) {
        val root = JSONObject(responseText)
        val elements = root.optJSONArray("elements") ?: JSONArray()
        val byRelId = mutableMapOf<Long, JSONObject>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") == "relation") {
                byRelId[el.optLong("id")] = el
            }
        }
        for (input in catalogueInputs) {
            val el = input.osmRelationId?.let { byRelId[it] }
            if (el == null) continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val members = el.optJSONArray("members") ?: JSONArray()
            var totalDistKm = 0.0
            var startLat = Double.NaN
            var startLng = Double.NaN
            var endLat = Double.NaN
            var endLng = Double.NaN
            var minLat = 90.0
            var maxLat = -90.0
            var minLng = 180.0
            var maxLng = -180.0
            var pointCount = 0

            for (m in 0 until members.length()) {
                val mem = members.optJSONObject(m) ?: continue
                if (mem.optString("type") != "way") continue
                val geom = mem.optJSONArray("geometry") ?: continue
                var prevLat = Double.NaN
                var prevLng = Double.NaN
                for (g in 0 until geom.length()) {
                    val pt = geom.optJSONObject(g) ?: continue
                    val lat = pt.optDouble("lat", Double.NaN)
                    val lon = pt.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    if (startLat.isNaN()) {
                        startLat = lat
                        startLng = lon
                    }
                    endLat = lat
                    endLng = lon
                    minLat = min(minLat, lat)
                    maxLat = max(maxLat, lat)
                    minLng = min(minLng, lon)
                    maxLng = max(maxLng, lon)
                    if (!prevLat.isNaN()) {
                        totalDistKm += haversineKm(prevLat, prevLng, lat, lon)
                    }
                    prevLat = lat
                    prevLng = lon
                    pointCount++
                }
            }
            if (pointCount < 2) continue
            val isCircular = haversineKm(startLat, startLng, endLat, endLng) <= 1.0
            val centerLat = (minLat + maxLat) / 2.0
            val centerLng = (minLng + maxLng) / 2.0
            val region = classifyRegion(centerLat, centerLng)

            val entry = JSONObject().apply {
                put("id", input.id)
                put("osmRelationId", input.osmRelationId)
                put("distanceKm", (totalDistKm * 10.0).roundToInt() / 10.0)
                put("region", region)
                put("isCircular", isCircular)
                put("startLat", startLat)
                put("startLng", startLng)
                put("endLat", endLat)
                put("endLng", endLng)
                put("minLat", minLat)
                put("maxLat", maxLat)
                put("minLng", minLng)
                put("maxLng", maxLng)
                val descDa = tags.optString("description:da").ifBlank { tags.optString("description") }
                if (descDa.isNotBlank()) put("description", descDa)
                val website = tags.optString("website").ifBlank { tags.optString("url") }.ifBlank { tags.optString("contact:website") }
                if (website.isNotBlank()) put("website", website)
                val operator = tags.optString("operator")
                if (operator.isNotBlank()) put("operator", operator)
                val from = tags.optString("from")
                if (from.isNotBlank()) put("from", from)
                val to = tags.optString("to")
                if (to.isNotBlank()) put("to", to)
                val tagDist = tags.optString("distance")
                if (tagDist.isNotBlank()) put("tagDistance", tagDist)
            }
            resultsArray.put(entry)
        }
    } else {
        println("WARN: Overpass could not be reached from this environment.")
    }

    val outDir = File("app/src/main/assets/catalogue")
    outDir.mkdirs()
    val metaRoot = JSONObject().apply {
        put("generatedAt", generatedAt)
        put("trails", resultsArray)
    }
    File(outDir, "catalogue_metadata.json").writeText(metaRoot.toString(2), Charsets.UTF_8)
    println("Wrote catalogue_metadata.json with ${resultsArray.length()} trails.")
}

main()
