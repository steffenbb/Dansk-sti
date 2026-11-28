package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TurnCue
import com.example.data.remote.HourlyForecastPoint
import com.example.data.remote.TrailWeatherInfo
import com.example.service.NavigationRainWatcher
import com.example.service.NavigationTrackingService
import com.example.service.NavigationVoiceGuide
import com.example.ui.screens.EmergencySosSheet
import com.example.ui.theme.DanskStiTheme
import com.example.util.GeoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Audit fix batch 3 – safety features that keep working with the app's UI closed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixBatch3Test {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val main: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example")

    private val cue = TurnCue(lat = 55.0, lng = 10.0, walkKm = 3.2, direction = "LEFT", angleDegrees = -80.0, instructionText = "Drej til venstre", pointIndex = 42)

    @Test
    fun voiceGuide_speaksEachCueOncePerAlertDistance() {
        val guide = NavigationVoiceGuide(app)
        assertNull("too far to speak", guide.cueKey(cue, 400.0))
        guide.markReadyForTesting(TextToSpeech(app) {})
        guide.speakCue(cue, 140.0)
        guide.speakCue(cue, 120.0)
        assertEquals("150 m alert once", 1, guide.spokenCountForTesting)
        guide.speakCue(cue, 25.0)
        assertEquals("then the 30 m alert", 2, guide.spokenCountForTesting)
        assertTrue(guide.lastSpokenTextForTesting!!.isNotBlank())
    }

    @Test
    fun service_speaksTurnCues_soTheScreenDoesntHaveTo() {
        val repo = DanskStiApp.getSettingsRepository(app)
        runBlocking { repo.setVoiceGuidance(true) }
        val service = Robolectric.buildService(NavigationTrackingService::class.java).create().get()
        val deadline = System.currentTimeMillis() + 5_000
        while (!service.voiceGuidanceEnabled && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        assertTrue("service follows the Stemmevejledning setting", service.voiceGuidanceEnabled)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        service.voiceGuide.markReadyForTesting(TextToSpeech(app) {})
        service.dispatchTurnCueAlert(cue, 120.0)
        assertEquals(1, service.voiceGuide.spokenCountForTesting)
        service.voiceGuidanceEnabled = false
        service.dispatchTurnCueAlert(cue.copy(walkKm = 4.0, pointIndex = 50), 120.0)
        assertEquals("setting off: silent", 1, service.voiceGuide.spokenCountForTesting)
        val screen = main.resolve("ui/screens/NavigationScreen.kt").readText()
        assertFalse("no second speech engine in the screen", screen.contains("TextToSpeech"))
        runBlocking { repo.setVoiceGuidance(false) }
    }

    private fun rainyWeather(nowMs: Long): TrailWeatherInfo {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Copenhagen")).apply { timeInMillis = nowMs }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val date = String.format(java.util.Locale.US, "%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        fun point(hour: Int, mm: Double) = HourlyForecastPoint(
            isoTime = String.format(java.util.Locale.US, "%sT%02d:00", date, hour), hourLabel = String.format(java.util.Locale.US, "%02d:00", hour),
            tempC = 15.0, precipitationProbabilityPct = 90, precipitationMm = mm, windSpeedMs = 4.0, windGustsMs = 7.0,
            weatherCode = 63, weatherIcon = "🌧️", conditionDescription = "Regn"
        )
        return TrailWeatherInfo(hourly48h = listOf(point(h, 2.0), point((h + 1) % 24, 2.0)), fetchedAtMillis = nowMs)
    }

    @Test
    fun rainWatcher_alertsFromTheService_respectingTheRefreshAndThrottleRules() = runBlocking {
        // 1 Jul 2026 12:10 Copenhagen
        val t0 = java.time.LocalDateTime.parse("2026-07-01T12:10:00").atZone(java.time.ZoneId.of("Europe/Copenhagen")).toInstant().toEpochMilli()
        var fetches = 0
        val watcher = NavigationRainWatcher(app) { _, _, now -> fetches++; rainyWeather(now) }
        val scope = CoroutineScope(Dispatchers.IO)
        watcher.onFix(scope, 55.0, 10.0, enabled = true, nowMs = t0)?.join()
        assertEquals(1, fetches)
        assertEquals(1, watcher.alertsPostedForTesting)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(nm).allNotifications.isNotEmpty())

        assertNull("not due again 5 min later in the same place", watcher.onFix(scope, 55.0, 10.0, true, t0 + 5 * 60_000L))
        watcher.onFix(scope, 55.0, 10.0, true, t0 + 61 * 60_000L)?.join()
        assertEquals("hourly refresh", 2, fetches)
        assertEquals("but at most one alert per 3 hours", 1, watcher.alertsPostedForTesting)
        assertNull("setting off: no fetch", watcher.onFix(scope, 55.0, 10.0, false, t0 + 4 * 3_600_000L))

        val helpers = main.resolve("ui/viewmodel/NavigationViewModelHelpers.kt").readText()
        assertFalse("the screen's viewmodel no longer posts rain alerts", helpers.contains("postRainAlertNotification("))
    }

    @Test
    fun rainWatcher_retriesTenMinutesAfterAFailure() = runBlocking {
        var fail = true
        var fetches = 0
        val watcher = NavigationRainWatcher(app) { _, _, now -> fetches++; if (fail) throw java.io.IOException("offline") else rainyWeather(now) }
        val scope = CoroutineScope(Dispatchers.IO)
        val t0 = 1_782_900_000_000L
        watcher.onFix(scope, 55.0, 10.0, true, t0)?.join()
        assertNull(watcher.onFix(scope, 55.0, 10.0, true, t0 + 5 * 60_000L))
        fail = false
        watcher.onFix(scope, 55.0, 10.0, true, t0 + 10 * 60_000L)?.join()
        assertEquals(2, fetches)
    }

    @Test
    fun sosSheet_showsShortCoordinates_andHowFreshTheFixIs() {
        assertEquals("55.67891° N, 10.12346° Ø", GeoUtils.formatDecimalWgs84(55.678912345, 10.123456789))
        val now = 1_782_900_000_000L
        compose.setContent {
            DanskStiTheme {
                EmergencySosSheet(lat = 55.678912345, lng = 10.123456789, accuracyM = 12.4f, fixTimeMs = now - 3 * 60_000L, onDismiss = {}, nowMs = now)
            }
        }
        compose.onNodeWithTag("sos_coordinates").assertTextEquals("55.67891° N, 10.12346° Ø")
        compose.onNodeWithTag("sos_fix_quality").assertTextEquals("±12 m · 3 min gammel")
        compose.onNodeWithTag("call_112_button").assertExists()
    }

    @Test
    fun sosSheet_withoutAFix_waitsForGps() {
        compose.setContent { DanskStiTheme { EmergencySosSheet(lat = null, lng = null, accuracyM = null, fixTimeMs = null, onDismiss = {}) } }
        compose.onNodeWithTag("sos_fix_quality").assertDoesNotExist()
        compose.onNodeWithTag("sos_coordinates").assertExists()
    }

    @Test
    fun kort_hasAnSosEntry() {
        val kort = main.resolve("ui/screens/KortScreen.kt").readText()
        assertTrue(kort.contains("testTag = \"kort_tool_sos\"") && kort.contains("KortSosSheet(onDismiss"))
    }
}
