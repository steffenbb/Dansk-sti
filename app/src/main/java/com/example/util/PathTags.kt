package com.example.util

object PathTags {
    val PATH_TAG_KEYS: Set<String> = setOf(
        "sac_scale",
        "trail_visibility",
        "smoothness",
        "tracktype",
        "lit",
        "foot",
        "access",
        "access:conditional",
        "foot:conditional",
        "ford",
        "step_count",
        "bridge",
        "informal",
        "bicycle",
        "horse",
        "tidal",
        "flood_prone",
        "incline",
        "seasonal",
        "name"
    )

    fun filterPathTags(tags: Map<String, String>): Map<String, String>? {
        if (tags.isEmpty()) return null
        val filtered = LinkedHashMap<String, String>()
        for ((k, v) in tags) {
            if (k in PATH_TAG_KEYS && v.isNotBlank()) {
                filtered[k] = v.trim()
            }
        }
        return if (filtered.isEmpty()) null else filtered
    }
}
