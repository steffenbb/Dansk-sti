package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.AppMoshi
import com.example.data.repository.TideEvent
import com.example.data.repository.TideStation
import com.example.data.repository.TideStationCache
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.isLegRequestCurrent
import com.example.util.MapIconUtils
import com.example.util.WaypointPhotoHelper
import com.example.util.network.UserAgentInterceptor
import com.example.util.network.sharedClientBuilder
import com.example.util.network.applyDanskStiDefaults
import com.example.util.routing.LegMode
import com.example.util.routing.RouteWaypoint
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Audit fix batch 4 – speed and memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixBatch4Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val main: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example")

    @Test
    fun plannerLeg_aSlowAnswerForAPinThatMovedIsDropped() {
        val vm = RouteBuilderViewModel(app)
        val a = RouteWaypoint(id = "a", lat = 55.0, lng = 10.0)
        val b = RouteWaypoint(id = "b", lat = 55.1, lng = 10.1)
        vm._uiState.update { it.copy(waypoints = listOf(a, b)) }
        assertTrue(vm.isLegRequestCurrent(a, b))
        // The user drags b again before the first answer arrives
        vm._uiState.update { it.copy(waypoints = listOf(a, b.copy(lat = 55.2))) }
        assertFalse("answer for b's old position is stale", vm.isLegRequestCurrent(a, b))
        assertTrue("answer for b's new position applies", vm.isLegRequestCurrent(a, b.copy(lat = 55.2)))
        vm._uiState.update { it.copy(waypoints = listOf(a.copy(legModeToNext = LegMode.STRAIGHT), b.copy(lat = 55.2))) }
        assertFalse("mode changed to straight line", vm.isLegRequestCurrent(a, b.copy(lat = 55.2)))
    }

    @Test
    fun clusterCountIcons_areBounded() {
        for (count in 2..400) MapIconUtils.getClusterCountDrawable(app, count)
        assertTrue(MapIconUtils.clusterCountCacheSizeForTesting() <= MapIconUtils.CLUSTER_COUNT_CACHE_SIZE)
        // Recent counts are still cached
        assertSame(MapIconUtils.getClusterCountDrawable(app, 400), MapIconUtils.getClusterCountDrawable(app, 400))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thumbnails_areSampledDown() {
        val file = File(app.filesDir, "photos/thumb_test.jpg").apply { parentFile?.mkdirs() }
        file.outputStream().use { Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val thumb = WaypointPhotoHelper.decodeSampled(file.absolutePath, 160)!!
        assertEquals("2000 px sampled by 8", 250, thumb.width)
        assertEquals(null, WaypointPhotoHelper.decodeSampled(File(app.filesDir, "missing.jpg").absolutePath, 160))
        val sources = listOf("ui/screens/planner/PlannerSheetRuteTab.kt", "ui/components/TripDiarySheet.kt", "ui/screens/RouteBuilderFeatureDialogs.kt")
        sources.forEach { assertFalse(it, main.resolve(it).readText().contains("BitmapFactory.decodeFile")) }
    }

    @Test
    fun httpClients_shareOnePoolAndOneUserAgent() {
        val a = sharedClientBuilder().build()
        val b = sharedClientBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        assertSame(a.connectionPool, b.connectionPool)
        assertSame(a.dispatcher, b.dispatcher)
        assertEquals(1, b.interceptors.count { it is UserAgentInterceptor })
        assertEquals("applying the defaults twice adds nothing", 1, sharedClientBuilder().applyDanskStiDefaults().interceptors().count { it is UserAgentInterceptor })
        val builders = main.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith("NetworkConstants.kt") }
            .count { it.readText().contains("OkHttpClient.Builder()") }
        assertEquals("every client derives from the shared one", 0, builders)
    }

    @Test
    fun moshi_isOneInstance_andRoundTripsWithGeneratedAdapters() {
        val files = main.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith("AppMoshi.kt") }
        assertEquals(0, files.count { it.readText().contains("KotlinJsonAdapterFactory") })
        val cache = TideStationCache(1L, listOf(TideStation("25149", "Esbjerg Havn", 55.4602, 8.4397, -120.0)))
        val adapter = AppMoshi.instance.adapter(TideStationCache::class.java)
        assertTrue("generated adapter is used", adapter.toString().contains("GeneratedJsonAdapter"))
        assertEquals(cache, adapter.fromJson(adapter.toJson(cache)))
        val ev = AppMoshi.instance.adapter(TideEvent::class.java)
        assertEquals(TideEvent(5L, true, 1.5), ev.fromJson(ev.toJson(TideEvent(5L, true, 1.5))))
    }

    @Test
    fun startup_opensTheDatabaseOffTheMainThread() {
        val src = main.resolve("DanskStiApp.kt").readText()
        val onCreate = src.substring(src.indexOf("override fun onCreate()"), src.indexOf("fun migrateLegacyWaypointPhotosFiles"))
        assertFalse(onCreate.contains("migrateLegacyWaypointPhotosFiles()"))
        val init = src.substring(src.indexOf("fun initDatabaseSafely()"))
        assertTrue("open happens inside the IO job", init.indexOf("db.openHelper.writableDatabase") > init.indexOf("CoroutineScope(Dispatchers.IO).launch"))
        val deadline = System.currentTimeMillis() + 10_000
        while (!(app as DanskStiApp).databaseOpenFinished.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue((app as DanskStiApp).databaseOpenFinished.value)
        assertTrue(main.resolve("MainActivity.kt").readText().contains("setKeepOnScreenCondition"))
    }

    @Test
    fun tiles_noRunBlockingOnTheMainThread() {
        assertFalse(main.resolve("util/DanskStiTileSource.kt").readText().contains("runBlocking("))
    }
}
