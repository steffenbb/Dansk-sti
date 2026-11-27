package com.example.util

import android.content.Context
import com.example.R

enum class OsmcColor(
    val key: String,
    val argbHex: Long,
    val daDefinite: String,
    val daIndefinite: String,
    val enName: String,
    val deDefinite: String,
    val deIndefinite: String
) {
    RED("red", 0xFFD32F2F, "røde", "rød", "red", "roten", "rot"),
    BLUE("blue", 0xFF1976D2, "blå", "blå", "blue", "blauen", "blau"),
    GREEN("green", 0xFF2E7D32, "grønne", "grøn", "green", "grünen", "grün"),
    YELLOW("yellow", 0xFFFBC02D, "gule", "gul", "yellow", "gelben", "gelb"),
    WHITE("white", 0xFFFFFFFF, "hvide", "hvid", "white", "weißen", "weiß"),
    BLACK("black", 0xFF212121, "sorte", "sort", "black", "schwarzen", "schwarz"),
    ORANGE("orange", 0xFFF57C00, "orange", "orange", "orange", "orangenen", "orange"),
    PURPLE("purple", 0xFF7B1FA2, "lilla", "lilla", "purple", "violetten", "violett"),
    BROWN("brown", 0xFF6D4C41, "brune", "brun", "brown", "braunen", "braun"),
    GRAY("gray", 0xFF757575, "grå", "grå", "grey", "grauen", "grau");

    fun definiteFor(locale: String): String = when {
        locale.startsWith("en", ignoreCase = true) -> enName
        locale.startsWith("de", ignoreCase = true) -> deDefinite
        else -> daDefinite
    }

    fun indefiniteFor(locale: String): String = when {
        locale.startsWith("en", ignoreCase = true) -> enName
        locale.startsWith("de", ignoreCase = true) -> deIndefinite
        else -> daIndefinite
    }
}

enum class OsmcBackgroundShape {
    SOLID,
    CIRCLE,
    FRAME,
    ROUND
}

enum class OsmcForegroundShape {
    BAR,
    STRIPE,
    DOT,
    CIRCLE,
    CROSS,
    TRIANGLE,
    DIAMOND,
    LOWER,
    CORNER,
    X,
    RECTANGLE,
    POINTER
}

data class OsmcForegroundSpec(
    val color: OsmcColor,
    val shape: OsmcForegroundShape
)

data class ParsedWaymark(
    val wayColor: OsmcColor? = null,
    val backgroundColor: OsmcColor? = null,
    val backgroundShape: OsmcBackgroundShape = OsmcBackgroundShape.SOLID,
    val foreground: OsmcForegroundSpec? = null,
    val foreground2: OsmcForegroundSpec? = null,
    val text: String? = null,
    val textColor: OsmcColor? = null,
    val descriptionDa: String
) {
    val primaryMarkColor: OsmcColor?
        get() = foreground?.color ?: wayColor

    fun description(context: Context? = null): String {
        if (context == null) return descriptionDa
        val locale = AppLocaleHelper.getCurrentLocaleCode(context)
        return OsmcSymbolParser.buildDescription(this, context, locale)
    }
}

object OsmcSymbolParser {

    private val COLOR_BY_NAME: Map<String, OsmcColor> = buildMap {
        for (c in OsmcColor.entries) {
            put(c.key, c)
        }
        put("grey", OsmcColor.GRAY)
    }

    private val BG_SUFFIXES: Map<String, OsmcBackgroundShape> = mapOf(
        "circle" to OsmcBackgroundShape.CIRCLE,
        "frame" to OsmcBackgroundShape.FRAME,
        "round" to OsmcBackgroundShape.ROUND
    )

    private val FG_SUFFIXES: Map<String, OsmcForegroundShape> = mapOf(
        "bar" to OsmcForegroundShape.BAR,
        "stripe" to OsmcForegroundShape.STRIPE,
        "dot" to OsmcForegroundShape.DOT,
        "circle" to OsmcForegroundShape.CIRCLE,
        "cross" to OsmcForegroundShape.CROSS,
        "triangle" to OsmcForegroundShape.TRIANGLE,
        "diamond" to OsmcForegroundShape.DIAMOND,
        "lower" to OsmcForegroundShape.LOWER,
        "corner" to OsmcForegroundShape.CORNER,
        "x" to OsmcForegroundShape.X,
        "rectangle" to OsmcForegroundShape.RECTANGLE,
        "pointer" to OsmcForegroundShape.POINTER
    )

    fun parseColor(raw: String?): OsmcColor? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().lowercase()
        return COLOR_BY_NAME[cleaned]
    }

    fun parseBackground(raw: String?): Pair<OsmcColor, OsmcBackgroundShape>? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().lowercase()
        val directColor = COLOR_BY_NAME[token]
        if (directColor != null) {
            return directColor to OsmcBackgroundShape.SOLID
        }
        val usIdx = token.indexOf('_')
        if (usIdx <= 0 || usIdx >= token.lastIndex) return null
        val colorPart = token.substring(0, usIdx)
        val shapePart = token.substring(usIdx + 1)
        val color = COLOR_BY_NAME[colorPart] ?: return null
        val shape = BG_SUFFIXES[shapePart] ?: return null
        return color to shape
    }

    fun parseForeground(raw: String?): OsmcForegroundSpec? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().lowercase()
        val usIdx = token.indexOf('_')
        if (usIdx <= 0 || usIdx >= token.lastIndex) return null
        val colorPart = token.substring(0, usIdx)
        val shapePart = token.substring(usIdx + 1)
        val color = COLOR_BY_NAME[colorPart] ?: return null
        val shape = FG_SUFFIXES[shapePart] ?: return null
        return OsmcForegroundSpec(color = color, shape = shape)
    }

    private fun looksLikeForegroundToken(raw: String): Boolean {
        val token = raw.trim().lowercase()
        val usIdx = token.indexOf('_')
        if (usIdx <= 0 || usIdx >= token.lastIndex) return false
        val colorPart = token.substring(0, usIdx)
        return COLOR_BY_NAME.containsKey(colorPart) || token.contains('_')
    }

    /**
     * Parses `osmc:symbol` of form:
     * `waycolor:background[:foreground[:foreground2]][:text[:textcolor]]`
     * with fallback to `colour` when `symbol` is blank or yields no recognized components.
     */
    fun parse(symbol: String?, colour: String? = null): ParsedWaymark? {
        if (!symbol.isNullOrBlank()) {
            val parts = symbol.trim().split(":")
            val wayColor = parseColor(parts.getOrNull(0))
            val bgPair = parseBackground(parts.getOrNull(1))
            val bgColor = bgPair?.first
            val bgShape = bgPair?.second ?: OsmcBackgroundShape.SOLID

            var fg1: OsmcForegroundSpec? = null
            var fg2: OsmcForegroundSpec? = null
            var text: String? = null
            var textColor: OsmcColor? = null

            val remaining = if (parts.size > 2) parts.subList(2, parts.size) else emptyList()
            var idx = 0
            if (idx < remaining.size) {
                val rawFg1 = remaining[idx].trim()
                val candFg1 = parseForeground(rawFg1)
                if (candFg1 != null || rawFg1.isEmpty() || looksLikeForegroundToken(rawFg1)) {
                    fg1 = candFg1
                    idx++
                    // Check if next token is a second foreground before optional text:textcolor
                    if (idx < remaining.size) {
                        val rawFg2 = remaining[idx].trim()
                        val candFg2 = parseForeground(rawFg2)
                        if (candFg2 != null || looksLikeForegroundToken(rawFg2)) {
                            fg2 = candFg2
                            idx++
                        } else if (rawFg2.isEmpty() && remaining.size - idx >= 2) {
                            // Empty foreground2 slot before text:textcolor
                            idx++
                        }
                    }
                }
            }

            if (idx < remaining.size) {
                val candText = remaining[idx].trim()
                if (candText.isNotEmpty() && parseForeground(candText) == null && !looksLikeForegroundToken(candText)) {
                    text = candText
                }
                idx++
                if (idx < remaining.size) {
                    textColor = parseColor(remaining[idx])
                }
            }

            if (wayColor != null || bgColor != null || fg1 != null || fg2 != null || !text.isNullOrBlank()) {
                val temp = ParsedWaymark(
                    wayColor = wayColor,
                    backgroundColor = bgColor,
                    backgroundShape = bgShape,
                    foreground = fg1,
                    foreground2 = fg2,
                    text = text,
                    textColor = textColor,
                    descriptionDa = ""
                )
                return temp.copy(descriptionDa = buildDescription(temp, null, "da"))
            }
        }

        val fallbackColor = parseColor(colour)
        if (fallbackColor != null) {
            val temp = ParsedWaymark(
                wayColor = fallbackColor,
                descriptionDa = ""
            )
            return temp.copy(descriptionDa = buildDescription(temp, null, "da"))
        }

        return null
    }

    internal fun buildDescription(
        waymark: ParsedWaymark,
        context: Context?,
        locale: String
    ): String {
        fun str(resId: Int, vararg args: Any): String? {
            if (context == null) return null
            return runCatching { AppLocaleHelper.str(context, resId, *args) }.getOrNull()
        }

        val fgColor = waymark.foreground?.color ?: waymark.wayColor
        val bgColor = waymark.backgroundColor
        val txt = waymark.text

        // Only fallback route colour (no background, no foreground shape, no text)
        val isColourOnly = waymark.foreground == null && bgColor == null && txt.isNullOrBlank() && waymark.wayColor != null
        if (isColourOnly) {
            val cIndef = waymark.wayColor!!.indefiniteFor(locale)
            return str(R.string.waymark_route_colour_fmt, cIndef) ?: "Rutens farve: $cIndef"
        }

        return when {
            fgColor != null && bgColor != null && !txt.isNullOrBlank() -> {
                val fgDef = fgColor.definiteFor(locale)
                val bgIndef = bgColor.indefiniteFor(locale)
                str(R.string.waymark_follow_color_on_bg_with_text_fmt, fgDef, txt, bgIndef)
                    ?: "Følg det $fgDef mærke «$txt» på $bgIndef baggrund"
            }
            fgColor != null && bgColor != null -> {
                val fgDef = fgColor.definiteFor(locale)
                val bgIndef = bgColor.indefiniteFor(locale)
                str(R.string.waymark_follow_color_on_bg_fmt, fgDef, bgIndef)
                    ?: "Følg det $fgDef mærke på $bgIndef baggrund"
            }
            fgColor != null && !txt.isNullOrBlank() -> {
                val fgDef = fgColor.definiteFor(locale)
                str(R.string.waymark_follow_color_with_text_fmt, fgDef, txt)
                    ?: "Følg det $fgDef mærke «$txt»"
            }
            !txt.isNullOrBlank() -> {
                str(R.string.waymark_follow_text_fmt, txt)
                    ?: "Følg mærket «$txt»"
            }
            fgColor != null -> {
                val fgDef = fgColor.definiteFor(locale)
                str(R.string.waymark_follow_color_fmt, fgDef)
                    ?: "Følg det $fgDef mærke"
            }
            bgColor != null -> {
                val bgDef = bgColor.definiteFor(locale)
                str(R.string.waymark_follow_color_fmt, bgDef)
                    ?: "Følg det $bgDef mærke"
            }
            else -> ""
        }
    }

    fun networkLabel(network: String?, context: Context? = null): String? {
        if (network.isNullOrBlank()) return null
        fun str(resId: Int, fallback: String): String {
            if (context == null) return fallback
            return runCatching { AppLocaleHelper.str(context, resId) }.getOrDefault(fallback)
        }
        return when (network.trim().lowercase()) {
            "iwn" -> str(R.string.waymark_network_iwn, "International rute")
            "nwn" -> str(R.string.waymark_network_nwn, "National rute")
            "rwn" -> str(R.string.waymark_network_rwn, "Regional rute")
            "lwn" -> str(R.string.waymark_network_lwn, "Lokal rute")
            else -> null
        }
    }
}
