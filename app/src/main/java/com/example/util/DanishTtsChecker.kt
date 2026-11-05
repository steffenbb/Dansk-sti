package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Round 10B Item 17: Before using voice guidance, check that a Danish TTS voice is installed;
 * if not, say so in settings.
 */
object DanishTtsChecker {

    val DANISH_LOCALE: Locale = Locale("da", "DK")

    @Volatile
    var isDanishVoiceInstalledOverrideForTests: Boolean? = null

    private val _isDanishVoiceInstalled = MutableStateFlow<Boolean?>(null)
    val isDanishVoiceInstalled: StateFlow<Boolean?> = _isDanishVoiceInstalled.asStateFlow()

    fun setDanishVoiceInstalledForTesting(installed: Boolean?) {
        isDanishVoiceInstalledOverrideForTests = installed
        _isDanishVoiceInstalled.value = installed
    }

    /**
     * Checks whether the given initialized [TextToSpeech] instance has an installed Danish voice.
     */
    fun isDanishTtsSupported(tts: TextToSpeech?): Boolean {
        isDanishVoiceInstalledOverrideForTests?.let {
            _isDanishVoiceInstalled.value = it
            return it
        }
        if (tts == null) {
            _isDanishVoiceInstalled.value = false
            return false
        }
        return try {
            val avail = tts.isLanguageAvailable(DANISH_LOCALE)
            if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED || avail < TextToSpeech.LANG_AVAILABLE) {
                _isDanishVoiceInstalled.value = false
                return false
            }
            val voices = runCatching { tts.voices }.getOrNull()
            if (!voices.isNullOrEmpty()) {
                val hasInstalledDanishVoice = voices.any { voice ->
                    val lang = voice.locale?.language?.lowercase(Locale.ROOT).orEmpty()
                    val isDa = lang == "da" || lang == "dan"
                    val features = voice.features ?: emptySet()
                    val notInstalled = features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    isDa && !notInstalled
                }
                _isDanishVoiceInstalled.value = hasInstalledDanishVoice
                hasInstalledDanishVoice
            } else {
                val supported = avail >= TextToSpeech.LANG_AVAILABLE
                _isDanishVoiceInstalled.value = supported
                supported
            }
        } catch (_: Exception) {
            _isDanishVoiceInstalled.value = false
            false
        }
    }

    /**
     * Asynchronously probes the device's TextToSpeech engine to check if a Danish voice is installed.
     */
    fun checkDanishVoiceAvailability(context: Context, onResult: (Boolean) -> Unit = {}) {
        isDanishVoiceInstalledOverrideForTests?.let { overrideVal ->
            _isDanishVoiceInstalled.value = overrideVal
            onResult(overrideVal)
            return
        }
        var engine: TextToSpeech? = null
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                val installed = if (status == TextToSpeech.SUCCESS) {
                    isDanishTtsSupported(engine)
                } else {
                    _isDanishVoiceInstalled.value = false
                    false
                }
                runCatching {
                    engine?.stop()
                    engine?.shutdown()
                }
                onResult(installed)
            }
        } catch (_: Exception) {
            _isDanishVoiceInstalled.value = false
            onResult(false)
        }
    }
}
