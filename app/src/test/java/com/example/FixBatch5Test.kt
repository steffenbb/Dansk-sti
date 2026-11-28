package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.service.OfflineDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** Audit fix batch 5 – housekeeping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixBatch5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val appDir: File = listOf(File("."), File("app")).first { it.resolve("src/main").exists() && it.resolve("build.gradle.kts").exists() }
    private val main: File = appDir.resolve("src/main")

    @Test
    fun templateLeftovers_areGone() {
        val gradle = appDir.resolve("build.gradle.kts").readText()
        assertFalse(gradle.contains("logging.interceptor"))
        val catalog = appDir.resolve("../gradle/libs.versions.toml").readText()
        listOf("coil-compose", "googleid", "androidx-credentials", "accompanist", "androidx-camera").forEach {
            assertFalse("$it still in the version catalogue", catalog.contains(it))
        }
        assertFalse(appDir.resolve("../.env.example").readText().contains("GEMINI"))
        assertFalse(main.resolve("java/com/example/util/network/NetworkConstants.kt").readText().contains("defaultSslPair"))
    }

    @Test
    fun unitsSetting_isRemoved() {
        assertFalse(main.resolve("java/com/example/data/local/SettingsRepository.kt").readText().contains("KEY_UNITS"))
    }

    @Test
    fun backup_skipsReDownloadableCaches() {
        listOf("res/xml/backup_rules.xml", "res/xml/data_extraction_rules.xml").forEach {
            val xml = main.resolve(it).readText()
            assertTrue(it, xml.contains("""<exclude domain="file" path="tides" />"""))
            assertTrue(it, xml.contains("""<exclude domain="file" path="restricted_areas" />"""))
        }
    }

    @Test
    fun offlineDownloads_haveAPerTrailNotificationWithCancel() {
        val a = OfflineDownloadWorker.notificationIdFor("trail_hærvejen")
        val b = OfflineDownloadWorker.notificationIdFor("area:1234")
        assertNotEquals(a, b)
        assertTrue(a in 20_000..24_095 && b in 20_000..24_095)
        val cancel = android.app.PendingIntent.getBroadcast(app, 0, android.content.Intent("x"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = OfflineDownloadWorker.buildProgressNotification(app, "Hærvejen", 5, 100, 5, cancel)
        assertEquals(1, n.actions.size)
        assertEquals("Annuller", n.actions[0].title.toString())
        val src = main.resolve("java/com/example/service/OfflineDownloadWorker.kt").readText()
        assertFalse("no hard-coded fallback name", src.contains("\"Vandrerute\""))
        assertEquals("Hiking route", ctx("en").getString(R.string.offline_download_fallback_name))
    }

    private fun ctx(lang: String) =
        app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(Locale(lang)) })

    @Test
    fun plurals_readRightForOne() {
        assertEquals("1 overnatning foreslået (2 dage)", ctx("da").resources.getQuantityString(R.plurals.planner_overnights_suggested, 1, 1, 2))
        assertEquals("3 overnatninger foreslået (4 dage)", ctx("da").resources.getQuantityString(R.plurals.planner_overnights_suggested, 3, 3, 4))
        assertEquals("Trapper: 1 sted", ctx("da").resources.getQuantityString(R.plurals.path_summary_steps, 1, 1))
        assertEquals("Steps: 2 places", ctx("en").resources.getQuantityString(R.plurals.path_summary_steps, 2, 2))
        assertEquals("Treppen: 1 Stelle", ctx("de").resources.getQuantityString(R.plurals.path_summary_steps, 1, 1))
    }

    @Test
    fun strings_stayInStepAcrossLanguages() {
        fun keys(d: String) = Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(main.resolve("res/$d/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val da = keys("values")
        assertEquals(da, keys("values-en"))
        assertEquals(da, keys("values-de"))
    }
}
