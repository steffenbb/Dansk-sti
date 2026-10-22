package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.example.data.local.DanskStiDatabase
import com.example.data.repository.DanskStiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class DanskStiApp : Application() {

    lateinit var database: DanskStiDatabase
        private set

    lateinit var repository: DanskStiRepository
        private set

    lateinit var settingsRepository: com.example.data.local.SettingsRepository
        private set

    private val _databaseError = MutableStateFlow<String?>(null)
    val databaseError: StateFlow<String?> = _databaseError.asStateFlow()

    val isDatabaseReady: Boolean
        get() = ::database.isInitialized && ::repository.isInitialized && _databaseError.value == null

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .penaltyDeathOnNetwork()
                    .build()
            )
        }

        settingsRepository = com.example.data.local.SettingsRepository(this)

        // Configure osmdroid user agent & internal directories safely
        // Keep the browsing cache in cacheDir
        try {
            val sharedPrefs = getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
            val osmdroidDir = java.io.File(filesDir, "osmdroid").apply { if (!exists()) mkdirs() }
            val osmdroidCache = java.io.File(cacheDir, "osmdroid_tiles").apply { if (!exists()) mkdirs() }
            Configuration.getInstance().load(this, sharedPrefs)
            Configuration.getInstance().osmdroidBasePath = osmdroidDir
            Configuration.getInstance().osmdroidTileCache = osmdroidCache
            Configuration.getInstance().userAgentValue = "DanskStiApp/1.0 (Android; Hiking in Denmark)"
        } catch (e: Exception) {
            Log.e("DanskStiApp", "Failed to configure osmdroid: ${e.message}", e)
        }

        initDatabaseSafely()
        createNotificationChannels()
    }

    /**
     * Requirement 17: Never delete the database.
     * If it fails to open, record the error text for the UI error screen + "Prøv igen" button and log the exception.
     */
    fun initDatabaseSafely() {
        try {
            val db = DanskStiDatabase.getInstance(this)
            db.openHelper.writableDatabase
            database = db
            repository = DanskStiRepository(database.dao(), this)
            _databaseError.value = null
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repository.initializePreseededDataIfEmpty()
                } catch (t: Throwable) {
                    Log.e("DanskStiApp", "Failed to preseed database: ${t.message}", t)
                }
            }
        } catch (e: Throwable) {
            Log.e("DanskStiApp", "Database init failed (never deleting database): ${e.message}", e)
            _databaseError.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun retryDatabaseInit() {
        DanskStiDatabase.resetInstance()
        initDatabaseSafely()
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
