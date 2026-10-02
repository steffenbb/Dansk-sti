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

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Configure osmdroid user agent & preferences
        val sharedPrefs = getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        Configuration.getInstance().load(this, sharedPrefs)
        Configuration.getInstance().userAgentValue = "DanskStiApp/1.0 (Android; Hiking in Denmark)"

        // Setup Room Database
        database = DanskStiDatabase.getInstance(this)
        repository = DanskStiRepository(database.dao(), this)

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
