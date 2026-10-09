package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.local.DanskStiDatabase
import com.example.data.repository.DanskStiRepository
import org.osmdroid.config.Configuration

class DanskStiApp : Application() {

    lateinit var database: DanskStiDatabase
        private set

    lateinit var repository: DanskStiRepository
        private set

    lateinit var settingsRepository: com.example.data.local.SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = com.example.data.local.SettingsRepository(this)

        // Configure osmdroid user agent & internal directories safely
        try {
            val sharedPrefs = getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
            val osmdroidDir = java.io.File(filesDir, "osmdroid").apply { if (!exists()) mkdirs() }
            val osmdroidCache = java.io.File(cacheDir, "osmdroid_tiles").apply { if (!exists()) mkdirs() }
            Configuration.getInstance().load(this, sharedPrefs)
            Configuration.getInstance().osmdroidBasePath = osmdroidDir
            Configuration.getInstance().osmdroidTileCache = osmdroidCache
            Configuration.getInstance().userAgentValue = "DanskStiApp/1.0 (Android; Hiking in Denmark)"
        } catch (e: Exception) {
            android.util.Log.e("DanskStiApp", "Failed to configure osmdroid: ${e.message}", e)
        }

        // Setup Room Database with auto-recovery
        try {
            database = DanskStiDatabase.getInstance(this)
            repository = DanskStiRepository(database.dao(), this)
        } catch (e: Exception) {
            android.util.Log.e("DanskStiApp", "Database init failed, recreating: ${e.message}", e)
            try {
                deleteDatabase("dansk_sti.db")
                database = DanskStiDatabase.getInstance(this)
                repository = DanskStiRepository(database.dao(), this)
            } catch (fatal: Exception) {
                android.util.Log.e("DanskStiApp", "Fatal database error: ${fatal.message}", fatal)
            }
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_NAVIGATION_ID,
                "Dansk Sti Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live hiking navigation stats and route tracking"
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Dansk Sti Trail Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when deviating from the hiking trail"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_NAVIGATION_ID = "dansk_sti_navigation_channel"
        const val CHANNEL_ALERTS_ID = "dansk_sti_alerts_channel"

        lateinit var instance: DanskStiApp
            private set
    }
}
