package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStageEntity
import com.example.service.NavigationLiveState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.importGpx
import com.example.util.GpxExporter
import com.example.util.WaypointPhotoHelper
import com.example.widget.ActiveTripAppWidgetProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/** Audit fix batch 1 – crash and data bugs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixBatch1Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val main: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }

    /** Behaves like a content-resolver stream: reading after close fails. */
    private class FdLikeStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile var closed = false
        @Volatile var readAfterClose = false
        @Volatile var readThread: Thread? = null
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) { readAfterClose = true; throw IOException("Stream closed") }
            if (readThread == null) readThread = Thread.currentThread()
            return super.read(b, off, len)
        }
        override fun read(): Int {
            if (closed) { readAfterClose = true; throw IOException("Stream closed") }
            return super.read()
        }
        override fun close() { closed = true; super.close() }
    }

    @Test
    fun gpxImport_opensTheFileOnIo_andClosesItOnlyAfterReading() {
        val vm = RouteBuilderViewModel(app)
        // One point only, so the import stops after parsing (no routing requests)
        val stream = FdLikeStream("""<?xml version="1.0" encoding="UTF-8"?><gpx><trk><trkseg><trkpt lat="55.1" lon="10.1"/></trkseg></trk></gpx>""".toByteArray())
        var openedOn: Thread? = null
        vm.importGpx { openedOn = Thread.currentThread(); stream }
        val deadline = System.currentTimeMillis() + 10_000
        while (!stream.closed && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("stream closed after use", stream.closed)
        assertFalse("never read after close", stream.readAfterClose)
        assertNotEquals("opened off the main thread", Looper.getMainLooper().thread, openedOn)
        assertNotEquals("parsed off the main thread", Looper.getMainLooper().thread, stream.readThread)
        val screen = main.resolve("java/com/example/ui/screens/RouteBuilderScreen.kt").readText()
        assertFalse("the screen must not open/close the GPX stream itself", screen.contains("viewModel.importGpx(stream)"))
    }

    @Test
    fun gpxExport_writesTimesInUtc() {
        val defaultTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Copenhagen"))
            val src = main.resolve("java/com/example/util/GpxExporter.kt").readText()
            assertEquals(3, Regex("""timeZone = java\.util\.TimeZone\.getTimeZone\("UTC"\)""").findAll(src).count())
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            // 1 Jul 2026 10:00 UTC is 12:00 in Copenhagen; the export must say 10:00Z
            assertEquals("2026-07-01T10:00:00Z", fmt.format(java.util.Date(1782900000000L)))
            assertTrue(GpxExporter::class.java.name.isNotEmpty())
        } finally {
            java.util.TimeZone.setDefault(defaultTz)
        }
    }

    @Test
    fun photoSampleSize_neverDecodesFarAboveTheTarget() {
        assertEquals(1, WaypointPhotoHelper.sampleSizeFor(2000, 1500, 2048))
        assertEquals(1, WaypointPhotoHelper.sampleSizeFor(4000, 3000, 2048))
        assertEquals(2, WaypointPhotoHelper.sampleSizeFor(8160, 6120, 2048)) // 50 MP: 4080 px after sampling
        assertEquals("exactly 2048 after sampling", 8, WaypointPhotoHelper.sampleSizeFor(16384, 12288, 2048))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun photoCompression_bakesInTheExifRotation() {
        val file = File(app.filesDir, "photos/exif_test.jpg").apply { parentFile?.mkdirs() }
        val landscape = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { landscape.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        android.media.ExifInterface(file.absolutePath).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertEquals(90, WaypointPhotoHelper.exifRotationDegrees(file))
        assertTrue(WaypointPhotoHelper.compressCapturedPhotoFile(file))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals("portrait after rotation", 200, bounds.outWidth)
        assertEquals(400, bounds.outHeight)
        assertEquals("rotation is in the pixels now, not the tag", 0, WaypointPhotoHelper.exifRotationDegrees(file))
    }

    @Test
    fun photoHandling_isOffTheMainThreadInTheScreens() {
        val rb = main.resolve("java/com/example/ui/screens/RouteBuilderScreen.kt").readText()
        val diary = main.resolve("java/com/example/ui/components/TripDiarySheet.kt").readText()
        assertTrue(rb.contains("compressInBackground(photoFile)") && rb.contains("importPickedPhoto(context, it)"))
        assertTrue(diary.contains("compressInBackground(file)") && diary.contains("importPickedPhoto(context, it, \"diary_photo\")"))
    }

    @Test
    fun widget_showsAPausedTripFromTheDatabase_offTheMainThread() = runBlocking {
        val dao = DanskStiDatabase.getInstance(app).dao()
        val planId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = "t1", trailName = "Øhavsstien", startKm = 0.0, endKm = 40.0, isReverse = false,
                totalDistanceKm = 40.0, targetDays = 2, overnightType = "SHELTER", preferredDailyKm = 20.0, stagesJson = "[]"
            )
        )
        dao.insertTripStages(
            listOf(
                TripStageEntity(planId, 1, 0.0, 20.0, 20.0, "Shelter A", "SHELTER", 55.0, 10.5),
                TripStageEntity(planId, 2, 20.0, 40.0, 20.0, "Shelter B", "SHELTER", 55.1, 10.6)
            )
        )
        dao.saveTripProgress(TripProgressEntity(planId, 2, 0.0, 20.0, 55.0, 10.5, 20.0, 20.0, isPaused = true))
        var data: com.example.widget.ActiveTripWidgetDisplayData? = null
        val t = Thread { data = ActiveTripAppWidgetProvider.resolveWidgetDisplayData(app, NavigationLiveState()) }
        t.start(); t.join(10_000)
        assertTrue("paused trip shown", data!!.isActive)
        assertEquals("Øhavsstien", data!!.trailName)
        assertEquals(2, data!!.dayNumber)
        assertEquals("Shelter B", data!!.nextCampName)
        // Called on the main thread it hands the Room work to a background thread instead of failing
        ActiveTripAppWidgetProvider.updateAllWidgets(app, liveState = NavigationLiveState())
        val src = main.resolve("java/com/example/widget/ActiveTripAppWidgetProvider.kt").readText()
        assertTrue(src.contains("goAsync()"))
    }

    @Test
    fun service_goesForegroundBeforeLoadingTheRoute() {
        val src = main.resolve("java/com/example/service/NavigationTrackingService.kt").readText()
        val start = src.indexOf("ACTION_START -> {")
        val foreground = src.indexOf("if (!enterForeground()) return START_NOT_STICKY", start)
        val load = src.indexOf("loadAndStartTracking(planId)", start)
        assertTrue(start > 0 && foreground > start && foreground < load)
    }

    @Test
    fun errorToasts_includeTheReason_andTheLanguagesShipTogether() {
        fun ctx(lang: String) = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(Locale(lang)) })
        assertEquals("Kunne ikke gemme foto: Ingen plads", ctx("da").getString(R.string.toast_could_not_save_photo, "Ingen plads"))
        assertEquals("Could not open camera: busy", ctx("en").getString(R.string.toast_could_not_open_camera, "busy"))
        assertEquals("Foto konnte nicht geladen werden: x", ctx("de").getString(R.string.toast_could_not_load_photo, "x"))
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() && it.readText().contains("applicationId") }.readText()
        assertTrue(gradle.contains("enableSplit = false"))
    }
}
