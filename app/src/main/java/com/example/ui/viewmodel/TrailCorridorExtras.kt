package com.example.ui.viewmodel

import com.example.util.OsmExtraLabels

/** Phase C6: pure helpers for the trail page's new place groups. */
internal object TrailCorridorExtras {

    /** The longest gap between consecutive sorted km values, as (startKm, endKm); null if fewer than 2. */
    fun longestGap(sortedKms: List<Double>): Pair<Double, Double>? {
        if (sortedKms.size < 2) return null
        var best: Pair<Double, Double>? = null
        for (i in 0 until sortedKms.size - 1) {
            val gap = sortedKms[i + 1] - sortedKms[i]
            if (best == null || gap > best.second - best.first) best = sortedKms[i] to sortedKms[i + 1]
        }
        return best
    }

    /** The likely-water place nearest the middle of [gap], if one lies strictly inside it. */
    fun likelyWaterInGap(gap: Pair<Double, Double>?, likelyWater: List<PoiSpotItem>): PoiSpotItem? {
        if (gap == null || gap.second - gap.first <= 0.0) return null
        val mid = (gap.first + gap.second) / 2.0
        return likelyWater
            .filter { it.kmOnRoute > gap.first && it.kmOnRoute < gap.second }
            .minByOrNull { kotlin.math.abs(it.kmOnRoute - mid) }
    }

    /** Counts per "Undervejs" group in a fixed display order: viewpoint, tower, historic, firepit, picnic, beach. */
    fun highlightCounts(items: List<PoiSpotItem>): List<Pair<String, Int>> {
        val order = listOf("viewpoint", "tower", "historic", "firepit", "picnic", "beach")
        val counts = items.mapNotNull { OsmExtraLabels.highlightGroup(it.type, it.subtype) }.groupingBy { it }.eachCount()
        return order.mapNotNull { g -> counts[g]?.takeIf { it > 0 }?.let { g to it } }
    }
}
