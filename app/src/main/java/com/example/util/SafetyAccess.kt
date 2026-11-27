package com.example.util

import android.content.Context
import com.example.R
import com.example.data.repository.RestrictedAreaKind
import java.util.Locale

/** One row of "Sikkerhed og adgang": a restricted area crossing or a path closure/access warning. */
data class SafetyAccessItem(
    val km: Double,
    val text: String,
    val lat: Double,
    val lng: Double,
    val isRestrictedArea: Boolean,
    val areaCrossing: AreaCrossing? = null
)

/** Phase D4/D5: Danish safety texts and the combined, km-sorted list. */
object SafetyAccess {

    /** Path warning types shown under "Sikkerhed og adgang". */
    val PATH_TYPES = setOf(
        PathFeatureType.SEASONAL_CLOSURE, PathFeatureType.NIGHT_CLOSURE, PathFeatureType.PRIVATE, PathFeatureType.TIDAL
    )

    private fun km(v: Double, locale: Locale) = com.example.ui.components.formatDanishKm(v, decimals = 1, locale = locale)

    private fun s(context: Context?, id: Int, fallback: String, vararg args: Any): String =
        AppLocaleHelper.strOrNull(context, id, *args) ?: String.format(Locale.ROOT, fallback, *args)

    /** The D4 warning for a crossing, or null for a reserve the route only passes near. */
    fun areaWarningText(c: AreaCrossing, context: Context?, locale: Locale): String? {
        val name = c.area.name
        return when (c.area.kind) {
            RestrictedAreaKind.MILITARY -> when {
                c.nearOnly && name != null -> s(context, R.string.safety_military_near_fmt,
                    "Ruten passerer tæt på et militært område: %1\$s (km %2\$s). Følg skilte og røde flag.", name, km(c.startKm, locale))
                c.nearOnly -> s(context, R.string.safety_military_near_unnamed_fmt,
                    "Ruten passerer tæt på et militært område (km %1\$s). Følg skilte og røde flag.", km(c.startKm, locale))
                name != null -> s(context, R.string.safety_military_crossing_fmt,
                    "Ruten går gennem et militært område: %1\$s (km %2\$s–%3\$s).", name, km(c.startKm, locale), km(c.endKm, locale))
                else -> s(context, R.string.safety_military_crossing_unnamed_fmt,
                    "Ruten går gennem et militært område (km %1\$s–%2\$s).", km(c.startKm, locale), km(c.endKm, locale))
            }
            RestrictedAreaKind.WILDLIFE_RESERVE -> when {
                c.nearOnly -> null
                name != null -> s(context, R.string.safety_reserve_crossing_fmt,
                    "Ruten går gennem %1\$s, et vildtreservat (km %2\$s–%3\$s).", name, km(c.startKm, locale), km(c.endKm, locale))
                else -> s(context, R.string.safety_reserve_crossing_unnamed_fmt,
                    "Ruten går gennem et vildtreservat (km %1\$s–%2\$s).", km(c.startKm, locale), km(c.endKm, locale))
            }
        }
    }

    /** "Militært område" / "Vildtreservat" or the area's own name, for the map tap sheet. */
    fun areaTitle(kind: RestrictedAreaKind, name: String?, context: Context?): String = name ?: when (kind) {
        RestrictedAreaKind.MILITARY -> s(context, R.string.safety_area_military_title, "Militært område")
        RestrictedAreaKind.WILDLIFE_RESERVE -> s(context, R.string.safety_area_reserve_title, "Vildtreservat")
    }

    /** General advice shown in the map tap sheet. */
    fun areaAdvice(kind: RestrictedAreaKind, context: Context?): String = when (kind) {
        RestrictedAreaKind.MILITARY -> s(context, R.string.safety_area_military_advice,
            "Når der skydes eller øves, kan adgang være forbudt – følg skilte og røde flag, og tjek skydetider på Forsvarets hjemmeside.")
        RestrictedAreaKind.WILDLIFE_RESERVE -> s(context, R.string.safety_area_reserve_advice,
            "Der kan være særlige adgangsregler, fx i yngletiden – følg skiltene.")
    }

    /** Link for the map sheet: the area's own website, else forsvaret.dk for military areas, none for reserves. */
    fun areaLink(kind: RestrictedAreaKind, website: String?): String? =
        website ?: if (kind == RestrictedAreaKind.MILITARY) "https://www.forsvaret.dk" else null

    /** Combines area crossings and SEASONAL/NIGHT/PRIVATE/TIDAL path warnings, sorted by km. */
    fun buildItems(
        crossings: List<AreaCrossing>,
        pathWarnings: List<PathFeatureWarning>,
        nowMs: Long,
        context: Context?,
        locale: Locale
    ): List<SafetyAccessItem> {
        val areaItems = crossings.mapNotNull { c ->
            val text = areaWarningText(c, context, locale) ?: return@mapNotNull null
            // Entering an area gets the full D4 advice; "passes close to" already ends with its own advice
            val advice = if (!c.nearOnly) " " + areaAdvice(c.area.kind, context) else ""
            SafetyAccessItem(c.startKm, text + advice, c.lat, c.lng, isRestrictedArea = true, areaCrossing = c)
        }
        val pathItems = pathWarnings.filter { it.type in PATH_TYPES }.map { w ->
            val closedToday = w.type == PathFeatureType.SEASONAL_CLOSURE &&
                w.closureRule?.let { !it.isUnparseable && it.isClosedOn(nowMs) } == true
            val title = if (closedToday) w.title + " " + s(context, R.string.safety_closed_today, "(lukket i dag)") else w.title
            SafetyAccessItem(
                km = w.startKm,
                text = s(context, R.string.safety_path_row_fmt, "km %1\$s · %2\$s", km(w.startKm, locale), title),
                lat = w.lat, lng = w.lng, isRestrictedArea = false
            )
        }
        return (areaItems + pathItems).sortedBy { it.km }
    }
}
