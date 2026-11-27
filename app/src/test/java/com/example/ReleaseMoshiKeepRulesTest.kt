package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds shrink with R8, which narrows field types of classes it is not told to keep
 * (List -> ArrayList). Moshi's reflective adapters then fail at runtime ("No JsonAdapter for class
 * java.util.ArrayList … for class TideStationCache", which stopped the app from starting).
 * This test finds every class handed to `moshi.adapter(X::class.java)`, follows the types of its
 * constructor properties, and requires a ProGuard keep rule for each one outside the packages
 * proguard-rules.pro already keeps as a whole.
 */
class ReleaseMoshiKeepRulesTest {

    private val root: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val proguard: String = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.exists() }.readText()

    private val wholePackageKeeps = Regex("""-keep class (com\.example\.[\w.]+)\.\*\*""").findAll(proguard).map { it.groupValues[1] }.toSet()

    /** simple class name -> (package, constructor property types) for every class/enum declared in main sources. */
    private val declarations: Map<String, Pair<String, List<String>>> by lazy {
        val out = HashMap<String, Pair<String, List<String>>>()
        root.resolve("java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val pkg = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@forEach
            Regex("""(?:data class|enum class|class) (\w+)\s*(?:\(([^{]*?)\)\s*(?::|\{|$))?""", RegexOption.MULTILINE).findAll(text).forEach { m ->
                val params = m.groupValues[2]
                val types = Regex(""":\s*([\w.<>, ?]+?)\s*(?:=|,|$)""").findAll(params)
                    .flatMap { Regex("""[A-Z]\w*""").findAll(it.groupValues[1]).map { t -> t.value } }.toList()
                out.putIfAbsent(m.groupValues[1], pkg to types)
            }
        }
        out
    }

    private fun isKept(pkg: String, name: String): Boolean =
        wholePackageKeeps.any { pkg == it || pkg.startsWith("$it.") } ||
            Regex("""-keep (class|enum) ${Regex.escape("$pkg.$name")}\b""").containsMatchIn(proguard)

    @Test
    fun everyMoshiReflectionClassIsKeptForRelease() {
        val targets = root.resolve("java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""adapter(?:<[^>]*>)?\(\s*([\w.]+)::class\.java""").findAll(it.readText()).map { m -> m.groupValues[1].substringAfterLast('.') } }
            .filter { it != "Map" && it != "List" }
            .toSet()
        assertTrue("expected to find Moshi adapter targets", targets.contains("TideStationCache"))

        val missing = sortedSetOf<String>()
        val seen = HashSet<String>()
        fun visit(name: String) {
            if (!seen.add(name)) return
            val (pkg, types) = declarations[name] ?: return // a library or JDK type
            if (!pkg.startsWith("com.example")) return
            if (!isKept(pkg, name)) missing.add("$pkg.$name")
            types.forEach { visit(it) }
        }
        targets.forEach { visit(it) }
        assertTrue(
            "Add ProGuard keep rules (app/proguard-rules.pro) for classes serialised with Moshi reflection: $missing",
            missing.isEmpty()
        )
    }
}
