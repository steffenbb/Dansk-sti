package com.example.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.example.data.model.TurnCue
import com.example.util.DanishTtsChecker
import com.example.util.routing.TurnCueGenerator
import java.util.Locale

/**
 * Spoken turn cues, owned by the tracking service (audit fix batch 3). They used to live in the
 * navigation screen, so they stopped when Android closed the app's UI during a long hike while
 * tracking carried on. Speaks each cue once per alert distance (150 m and 30 m).
 */
class NavigationVoiceGuide(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var voiceLocale: Locale = DanishTtsChecker.DANISH_LOCALE
    private var lastSpokenKey: String? = null

    var spokenCountForTesting = 0
        private set
    var lastSpokenTextForTesting: String? = null
        private set

    /** Starts the speech engine when enabled, shuts it down when not. Safe from any thread. */
    fun setEnabled(enabled: Boolean) {
        main.post {
            if (enabled && tts == null) start() else if (!enabled) shutdownNow()
        }
    }

    private fun start() {
        runCatching {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                val locale = if (status == TextToSpeech.SUCCESS) DanishTtsChecker.resolveEffectiveTtsLocale(context, engine) else null
                if (locale != null) {
                    engine?.language = locale
                    voiceLocale = locale
                    ready = true
                } else {
                    ready = false
                }
            }
            tts = engine
        }
    }

    /** Key that changes once per cue and alert distance, or null when the cue is too far to speak. */
    internal fun cueKey(cue: TurnCue, distanceMeters: Double): String? {
        val bucket = when {
            distanceMeters <= TurnCueGenerator.CUE_SECOND_ALERT_METERS -> "30"
            distanceMeters <= TurnCueGenerator.CUE_FIRST_ALERT_METERS -> "150"
            else -> return null
        }
        return "${cue.pointIndex}_${cue.walkKm}_$bucket"
    }

    /** Speaks [cue] if the engine is ready and this cue/distance hasn't been spoken yet. */
    fun speakCue(cue: TurnCue, distanceMeters: Double) {
        val key = cueKey(cue, distanceMeters) ?: return
        if (key == lastSpokenKey) return
        val engine = tts
        if (!ready || engine == null) return
        lastSpokenKey = key
        val text = DanishTtsChecker.formatSpeechCueText(context, cue, distanceMeters, voiceLocale)
        spokenCountForTesting++
        lastSpokenTextForTesting = text
        runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, key) }
    }

    fun shutdown() = main.post { shutdownNow() }

    private fun shutdownNow() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        lastSpokenKey = null
    }

    /** Test hook: behave as if the engine finished starting with [locale]. */
    internal fun markReadyForTesting(engine: TextToSpeech?, locale: Locale = DanishTtsChecker.DANISH_LOCALE) {
        tts = engine
        voiceLocale = locale
        ready = true
    }
}
