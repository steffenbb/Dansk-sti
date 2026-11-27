package com.example.util

import android.content.Context
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.util.routing.BRouterGeoJsonParser
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.WayRangeUtils
import kotlin.math.max

data class PathRange(
    val fromKm: Double,
    val toKm: Double,
    val highway: String?,
    val surface: String?,
    val tags: Map<String, String> = emptyMap()
)

enum class PathFeatureType {
    STEPS,
    FORD,
    PRIVATE,
    SEASONAL_CLOSURE,
    NIGHT_CLOSURE,
    TIDAL,
    UNCLEAR,
    ROUGH,
    INFORMAL,
    BOARDWALK
}

data class PathFeatureSummary(
    val stepsCount: Int = 0,
    val fordCount: Int = 0,
    val privateKm: Double = 0.0,
    val horseKm: Double = 0.0,
    val bikeKm: Double = 0.0,
    val litKm: Double = 0.0,
    val roughOrUnclearKm: Double = 0.0,
    val sandKm: Double = 0.0,
    val informalKm: Double = 0.0,
    val tidalKm: Double = 0.0
) {
    val hasAnyFeature: Boolean
        get() = stepsCount > 0 ||
            fordCount > 0 ||
            privateKm > 0.01 ||
            horseKm > 0.01 ||
            bikeKm > 0.01 ||
            litKm > 0.01 ||
            roughOrUnclearKm > 0.01 ||
            sandKm > 0.01 ||
            informalKm > 0.01 ||
            tidalKm > 0.01
}

data class PathFeatureWarning(
    val type: PathFeatureType,
    val startKm: Double,
    val endKm: Double,
    val lat: Double,
    val lng: Double,
    val title: String,
    val detail: String? = null,
    val closureRule: ClosureRule? = null,
    val stepCount: Int? = null
)

data class PathFeatureAnalysisResult(
    val summary: PathFeatureSummary,
    val warnings: List<PathFeatureWarning>
)

object PathRangeBuilder {

    fun fromTrailSegments(segments: List<TrailSegmentDto>): List<PathRange> {
        val result = mutableListOf<PathRange>()
        var cumWalkKm = 0.0

        for (seg in segments) {
            if (seg.type == TrailSegmentType.FERRY) continue
            val segLocalKm = max(seg.lengthKm, WayRangeUtils.segmentWalkKm(seg))
            val normRanges = WayRangeUtils.normalizeSegmentWayRanges(seg, cumWalkKm, segLocalKm)
            if (normRanges.isNotEmpty()) {
                for (r in normRanges) {
                    val fKm = cumWalkKm + r.fromKm.coerceAtLeast(0.0)
                    val tKm = cumWalkKm + r.toKm.coerceAtLeast(r.fromKm)
                    if (tKm >= fKm) {
                        result.add(
                            PathRange(
                                fromKm = fKm,
                                toKm = tKm,
                                highway = r.highway ?: seg.highway,
                                surface = r.surface ?: seg.surface,
                                tags = r.pathTags ?: emptyMap()
                            )
                        )
                    }
                }
                val rangesSpan = normRanges.maxOfOrNull { it.toKm } ?: 0.0
                cumWalkKm += max(segLocalKm, rangesSpan)
            } else {
                if (segLocalKm > 0.0 && (!seg.highway.isNullOrBlank() || !seg.surface.isNullOrBlank())) {
                    result.add(
                        PathRange(
                            fromKm = cumWalkKm,
                            toKm = cumWalkKm + segLocalKm,
                            highway = seg.highway,
                            surface = seg.surface,
                            tags = emptyMap()
                        )
                    )
                }
                cumWalkKm += segLocalKm
            }
        }
        return result
    }

    fun fromLegs(orderedLegs: List<LegGeometryData>): List<PathRange> {
        val result = mutableListOf<PathRange>()
        var cumWalkKm = 0.0

        for (leg in orderedLegs) {
            val isFerryLeg = leg.legMode == LegMode.STRAIGHT && leg.sourceName.contains("ferry", ignoreCase = true)
            if (isFerryLeg) continue

            val hasTrailRanges = leg.trailSegments.any { it.type == TrailSegmentType.WALK && it.wayTagRanges.isNotEmpty() }
            if (hasTrailRanges) {
                var legLocalWalkKm = 0.0
                for (seg in leg.trailSegments) {
                    if (seg.type == TrailSegmentType.FERRY) continue
                    val segLocalKm = max(seg.lengthKm, WayRangeUtils.segmentWalkKm(seg))
                    val normRanges = WayRangeUtils.normalizeSegmentWayRanges(seg, legLocalWalkKm, segLocalKm)
                    for (r in normRanges) {
                        val fKm = cumWalkKm + legLocalWalkKm + r.fromKm.coerceAtLeast(0.0)
                        val tKm = cumWalkKm + legLocalWalkKm + r.toKm.coerceAtLeast(r.fromKm)
                        if (tKm >= fKm) {
                            result.add(
                                PathRange(
                                    fromKm = fKm,
                                    toKm = tKm,
                                    highway = r.highway ?: seg.highway,
                                    surface = r.surface ?: seg.surface,
                                    tags = r.pathTags ?: emptyMap()
                                )
                            )
                        }
                    }
                    val rangesSpan = normRanges.maxOfOrNull { it.toKm } ?: 0.0
                    legLocalWalkKm += max(segLocalKm, rangesSpan)
                }
                cumWalkKm += max(leg.lengthKm, legLocalWalkKm)
            } else if (leg.points.size >= 2 && leg.wayInfo.isNotEmpty()) {
                var legAccKm = 0.0
                for (i in 0 until leg.points.size - 1) {
                    val p0 = leg.points[i]
                    val p1 = leg.points[i + 1]
                    val edgeKm = BRouterGeoJsonParser.distanceMeters(p0.lat, p0.lng, p1.lat, p1.lng) / 1000.0
                    val wi = leg.wayInfo.getOrNull(i)
                    val hw = wi?.highway
                    val surf = wi?.surface
                    val tags = if (wi != null) PathTags.filterPathTags(wi.rawTags) ?: emptyMap() else emptyMap()
                    val fKm = cumWalkKm + legAccKm
                    val tKm = fKm + edgeKm
                    val last = result.lastOrNull()
                    if (last != null &&
                        kotlin.math.abs(last.toKm - fKm) < 1e-6 &&
                        last.highway == hw &&
                        last.surface == surf &&
                        last.tags == tags
                    ) {
                        result[result.lastIndex] = last.copy(toKm = tKm)
                    } else {
                        result.add(
                            PathRange(
                                fromKm = fKm,
                                toKm = tKm,
                                highway = hw,
                                surface = surf,
                                tags = tags
                            )
                        )
                    }
                    legAccKm += edgeKm
                }
                cumWalkKm += max(leg.lengthKm, legAccKm)
            } else {
                cumWalkKm += leg.lengthKm
            }
        }
        return result
    }
}

object PathFeatureAnalyzer {

    private val HARD_SAC_SCALES = setOf(
        "mountain_hiking",
        "demanding_mountain_hiking",
        "alpine_hiking",
        "demanding_alpine_hiking",
        "difficult_alpine_hiking"
    )

    private val ROUGH_SMOOTHNESS = setOf(
        "very_bad",
        "horrible",
        "very_horrible",
        "impassable"
    )

    private val UNCLEAR_VISIBILITY = setOf(
        "bad",
        "horrible",
        "no"
    )

    private val ALLOWED_FOOT_VALUES = setOf(
        "yes",
        "designated",
        "permissive"
    )

    private val RESTRICTED_ACCESS_VALUES = setOf(
        "private",
        "no"
    )

    private data class RawCandidateWarning(
        val type: PathFeatureType,
        val startKm: Double,
        val endKm: Double,
        val stepCount: Int? = null,
        val closureRule: ClosureRule? = null
    )

    fun analyze(
        ranges: List<PathRange>,
        pointAtKm: (Double) -> GeoPointDto?,
        context: Context? = null
    ): PathFeatureAnalysisResult {
        if (ranges.isEmpty()) {
            return PathFeatureAnalysisResult(PathFeatureSummary(), emptyList())
        }

        var privateKm = 0.0
        var horseKm = 0.0
        var bikeKm = 0.0
        var litKm = 0.0
        var roughOrUnclearKm = 0.0
        var sandKm = 0.0
        var informalKm = 0.0
        var tidalKm = 0.0

        val rawCandidates = mutableListOf<RawCandidateWarning>()

        for (range in ranges) {
            val fKm = range.fromKm
            val tKm = range.toKm.coerceAtLeast(fKm)
            val lenKm = (tKm - fKm).coerceAtLeast(0.0)
            val hw = range.highway?.trim()?.lowercase()
            val surf = range.surface?.trim()?.lowercase()
            val tags = range.tags

            val foot = tags["foot"]?.trim()?.lowercase()
            val access = tags["access"]?.trim()?.lowercase()
            val ford = tags["ford"]?.trim()?.lowercase()
            val stepCountVal = tags["step_count"]?.trim()?.toIntOrNull()
            val tidal = tags["tidal"]?.trim()?.lowercase()
            val floodProne = tags["flood_prone"]?.trim()?.lowercase()
            val visibility = tags["trail_visibility"]?.trim()?.lowercase()
            val smoothness = tags["smoothness"]?.trim()?.lowercase()
            val sacScale = tags["sac_scale"]?.trim()?.lowercase()
            val informal = tags["informal"]?.trim()?.lowercase()
            val bridge = tags["bridge"]?.trim()?.lowercase()
            val horse = tags["horse"]?.trim()?.lowercase()
            val bicycle = tags["bicycle"]?.trim()?.lowercase()
            val lit = tags["lit"]?.trim()?.lowercase()

            // Summary-only & hybrid distance accumulations
            if (horse == "designated") {
                horseKm += lenKm
            }
            if ((bicycle == "designated" && (hw == "path" || hw == "footway")) || hw == "cycleway") {
                bikeKm += lenKm
            }
            if (lit == "yes") {
                litKm += lenKm
            }
            if (surf == "sand") {
                sandKm += lenKm
            }

            // 1. STEPS
            if (hw == "steps") {
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.STEPS,
                        startKm = fKm,
                        endKm = tKm,
                        stepCount = stepCountVal
                    )
                )
            }

            // 2. FORD
            if (ford == "yes" || ford == "stepping_stones") {
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.FORD,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            // 3. PRIVATE
            val isFootExplicitlyAllowed = foot in ALLOWED_FOOT_VALUES
            val isPrivateOrNo = (access in RESTRICTED_ACCESS_VALUES || foot in RESTRICTED_ACCESS_VALUES) && !isFootExplicitlyAllowed
            if (isPrivateOrNo) {
                privateKm += lenKm
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.PRIVATE,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            // 4 & 5. SEASONAL_CLOSURE & NIGHT_CLOSURE from foot:conditional / access:conditional
            val condStrings = listOfNotNull(tags["foot:conditional"], tags["access:conditional"])
            if (condStrings.isNotEmpty()) {
                val parsedRules = condStrings.flatMap { ConditionalAccessParser.parse(it) }
                    .distinctBy { "${it.dateRange}_${it.timeText}_${it.raw}_${it.isUnparseable}" }
                for (rule in parsedRules) {
                    when {
                        rule.dateRange != null || rule.isUnparseable -> {
                            rawCandidates.add(
                                RawCandidateWarning(
                                    type = PathFeatureType.SEASONAL_CLOSURE,
                                    startKm = fKm,
                                    endKm = tKm,
                                    closureRule = rule
                                )
                            )
                        }
                        rule.timeText != null -> {
                            rawCandidates.add(
                                RawCandidateWarning(
                                    type = PathFeatureType.NIGHT_CLOSURE,
                                    startKm = fKm,
                                    endKm = tKm,
                                    closureRule = rule
                                )
                            )
                        }
                    }
                }
            }

            // 6. TIDAL
            if (tidal == "yes" || floodProne == "yes") {
                tidalKm += lenKm
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.TIDAL,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            // 7. UNCLEAR
            val isUnclear = visibility in UNCLEAR_VISIBILITY
            if (isUnclear) {
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.UNCLEAR,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            // 8. ROUGH
            val isRough = smoothness in ROUGH_SMOOTHNESS || sacScale in HARD_SAC_SCALES
            if (isRough) {
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.ROUGH,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            if (isUnclear || isRough) {
                roughOrUnclearKm += lenKm
            }

            // 9. INFORMAL
            if (informal == "yes") {
                informalKm += lenKm
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.INFORMAL,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }

            // 10. BOARDWALK
            if (bridge == "boardwalk") {
                rawCandidates.add(
                    RawCandidateWarning(
                        type = PathFeatureType.BOARDWALK,
                        startKm = fKm,
                        endKm = tKm
                    )
                )
            }
        }

        // Merge ranges of the same type (and compatible closure rule) when < 50 m (0.05 km) apart
        val mergedCandidates = mergeCandidateWarnings(rawCandidates)

        // Drop warnings shorter than 10 m (0.01 km), except STEPS and FORD
        val filteredCandidates = mergedCandidates.filter { cand ->
            val len = cand.endKm - cand.startKm
            cand.type == PathFeatureType.STEPS ||
                cand.type == PathFeatureType.FORD ||
                len >= 0.01 - 1e-9
        }

        val stepsCount = filteredCandidates.count { it.type == PathFeatureType.STEPS }
        val fordCount = filteredCandidates.count { it.type == PathFeatureType.FORD }

        val localeCode = if (context != null) {
            AppLocaleHelper.getCurrentLocaleCode(context)
        } else {
            "da"
        }

        val finalWarnings = filteredCandidates
            .sortedWith(compareBy<RawCandidateWarning> { it.startKm }.thenBy { it.type.ordinal })
            .map { cand ->
                val pt = pointAtKm(cand.startKm)
                val lat = pt?.lat ?: 0.0
                val lng = pt?.lng ?: 0.0
                val (title, detail) = formatWarningText(cand, context, localeCode)
                PathFeatureWarning(
                    type = cand.type,
                    startKm = cand.startKm,
                    endKm = cand.endKm,
                    lat = lat,
                    lng = lng,
                    title = title,
                    detail = detail,
                    closureRule = cand.closureRule,
                    stepCount = cand.stepCount
                )
            }

        val summary = PathFeatureSummary(
            stepsCount = stepsCount,
            fordCount = fordCount,
            privateKm = privateKm,
            horseKm = horseKm,
            bikeKm = bikeKm,
            litKm = litKm,
            roughOrUnclearKm = roughOrUnclearKm,
            sandKm = sandKm,
            informalKm = informalKm,
            tidalKm = tidalKm
        )

        return PathFeatureAnalysisResult(summary = summary, warnings = finalWarnings)
    }

    private fun mergeCandidateWarnings(raw: List<RawCandidateWarning>): List<RawCandidateWarning> {
        if (raw.isEmpty()) return emptyList()
        val grouped = raw.groupBy { cand ->
            val ruleKey = cand.closureRule?.let { "${it.dateRange}_${it.timeText}_${it.raw}_${it.isUnparseable}" } ?: ""
            "${cand.type.name}::$ruleKey"
        }

        val mergedAll = mutableListOf<RawCandidateWarning>()
        for ((_, group) in grouped) {
            val sorted = group.sortedBy { it.startKm }
            var current = sorted.first()
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.startKm - current.endKm < 0.05 + 1e-9) {
                    val combinedSteps = when {
                        current.stepCount != null && next.stepCount != null -> current.stepCount + next.stepCount
                        current.stepCount != null -> current.stepCount
                        else -> next.stepCount
                    }
                    current = current.copy(
                        endKm = max(current.endKm, next.endKm),
                        stepCount = combinedSteps
                    )
                } else {
                    mergedAll.add(current)
                    current = next
                }
            }
            mergedAll.add(current)
        }
        return mergedAll
    }

    private fun formatWarningText(
        cand: RawCandidateWarning,
        context: Context?,
        localeCode: String
    ): Pair<String, String?> {
        fun str(resId: Int, vararg args: Any): String? {
            if (context == null) return null
            return runCatching { AppLocaleHelper.str(context, resId, *args) }.getOrNull()
        }

        return when (cand.type) {
            PathFeatureType.STEPS -> {
                val sc = cand.stepCount
                if (sc != null && sc > 0) {
                    val text = str(R.string.path_warn_steps_count_fmt, sc) ?: "Trapper ($sc trin)"
                    text to "$sc trin"
                } else {
                    val text = str(R.string.path_warn_steps) ?: "Trapper"
                    text to null
                }
            }
            PathFeatureType.FORD -> {
                val text = str(R.string.path_warn_ford) ?: "Vadested – du kan få våde fødder"
                text to null
            }
            PathFeatureType.PRIVATE -> {
                val text = str(R.string.path_warn_private) ?: "Privat vej/sti – adgang kan være begrænset"
                text to null
            }
            PathFeatureType.SEASONAL_CLOSURE -> {
                val rule = cand.closureRule
                if (rule != null && rule.isUnparseable) {
                    val text = str(R.string.path_warn_conditional_raw_fmt, rule.raw)
                        ?: "Adgang begrænset: ${rule.raw}"
                    text to rule.raw
                } else {
                    val period = rule?.displayPeriod(localeCode) ?: ""
                    val text = str(R.string.path_warn_seasonal_closure_fmt, period)
                        ?: "Lukket $period"
                    text to period
                }
            }
            PathFeatureType.NIGHT_CLOSURE -> {
                val timeText = cand.closureRule?.timeText ?: cand.closureRule?.raw ?: ""
                val text = str(R.string.path_warn_night_closure_fmt, timeText)
                    ?: "Lukket om natten ($timeText)"
                text to timeText
            }
            PathFeatureType.TIDAL -> {
                val text = str(R.string.path_warn_tidal)
                    ?: "Kan stå under vand ved højvande eller oversvømmelse"
                text to null
            }
            PathFeatureType.UNCLEAR -> {
                val text = str(R.string.path_warn_unclear) ?: "Utydelig sti"
                text to null
            }
            PathFeatureType.ROUGH -> {
                val text = str(R.string.path_warn_rough)
                    ?: "Ujævnt underlag – godt fodtøj anbefales"
                text to null
            }
            PathFeatureType.INFORMAL -> {
                val text = str(R.string.path_warn_informal) ?: "Uofficiel sti"
                text to null
            }
            PathFeatureType.BOARDWALK -> {
                val text = str(R.string.path_warn_boardwalk) ?: "Spang – kan være glat"
                text to null
            }
        }
    }
}
