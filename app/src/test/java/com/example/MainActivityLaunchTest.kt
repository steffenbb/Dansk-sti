package com.example

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

    @Test
    fun testLaunchMainActivity() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            org.junit.Assert.assertNotNull(activity)
        }
    }

    @Test
    fun testNavigateToTrailDetail() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
    }

    @Test
    fun testMapViewZoomBeforeLayout() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val mapView = org.osmdroid.views.MapView(activity)
            mapView.zoomToBoundingBox(org.osmdroid.util.BoundingBox(56.0, 12.0, 55.0, 11.0), false)
        }
    }
}

