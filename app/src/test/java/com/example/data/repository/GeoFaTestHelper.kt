package com.example.data.repository

import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import java.io.File
import java.util.zip.GZIPInputStream

internal fun GeoFaRepository.loadBundledSheltersFromDiskOrAssets(): List<ShelterEntity> {
    val json = readAssetOrDiskGeoJsonGz("shelters.geojson.gz") ?: return emptyList()
    return parseSheltersGeoJson(json)
}

internal fun GeoFaRepository.loadBundledFriTeltningFromDiskOrAssets(): List<FriTeltningEntity> {
    val json = readAssetOrDiskGeoJsonGz("fri_teltning.geojson.gz") ?: return emptyList()
    return parseFriTeltningGeoJson(json)
}

internal fun GeoFaRepository.loadBundledPoisFromDiskOrAssets(): List<PoiEntity> {
    val json = readAssetOrDiskGeoJsonGz("toilets_water.geojson.gz") ?: return emptyList()
    return parsePoisGeoJson(json)
}

internal fun GeoFaRepository.readAssetOrDiskGeoJsonGz(assetName: String): String? {
    readAssetGeoJsonGz(assetName)?.let { return it }
    val fileCandidates = listOf(
        File("src/main/assets/geofa/$assetName"),
        File("app/src/main/assets/geofa/$assetName")
    )
    for (file in fileCandidates) {
        if (file.exists()) {
            try {
                return file.inputStream().use { input ->
                    GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } catch (_: Exception) {
            }
        }
    }
    return null
}
