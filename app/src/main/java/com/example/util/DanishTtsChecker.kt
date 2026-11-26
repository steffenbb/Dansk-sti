package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Before using voice guidance, check that a Danish TTS voice is installed;
 * if not, say so in settings.
 */
object DanishTtsChecker {

    val DANISH_LOCALE: Locale = Locale("da", "DK")

    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var isDanishVoiceInstalledOverrideForTests: Boolean? = null

    private val _isDanishVoiceInstalled = MutableStateFlow<Boolean?>(null)
    val isDanishVoiceInstalled: StateFlow<Boolean?> = _isDanishVoiceInstalled.asStateFlow()

    @androidx.annotation.VisibleForTesting
    internal fun setDanishVoiceInstalledForTesting(installed: Boolean?) {
        isDanishVoiceInstalledOverrideForTests = installed
        _isDanishVoiceInstalled.value = installed
    }

    /**
     * Checks whether the given initialized [TextToSpeech] instance has an installed Danish voice.
     */
    fun isDanishTtsSupported(tts: TextToSpeech?): Boolean = isTtsLocaleSupported(tts, DANISH_LOCALE)

    fun resolveTtsLocale(context: Context): Locale {
        val cfgLocale = AppLocaleHelper.configurationLocale(AppLocaleHelper.createLocalizedContext(context))
        return when (cfgLocale.language.lowercase(Locale.ROOT)) {
            "en" -> Locale.ENGLISH
            "de" -> Locale.GERMAN
            else -> DANISH_LOCALE
        }
    }

    fun isTtsLocaleSupported(tts: TextToSpeech?, targetLocale: Locale = DANISH_LOCALE): Boolean {
        isDanishVoiceInstalledOverrideForTests?.let {
            _isDanishVoiceInstalled.value = it
            return it
        }
        if (tts == null) {
            _isDanishVoiceInstalled.value = false
            return false
        }
        return try {
            val avail = tts.isLanguageAvailable(targetLocale)
            if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED || avail < TextToSpeech.LANG_AVAILABLE) {
                _isDanishVoiceInstalled.value = false
                return false
            }
            val voices = runCatching { tts.voices }.getOrNull()
            if (!voices.isNullOrEmpty()) {
                val targetLang = targetLocale.language.lowercase(Locale.ROOT)
                val hasInstalledVoice = voices.any { voice ->
                    val lang = voice.locale?.language?.lowercase(Locale.ROOT).orEmpty()
                    val matches = lang == targetLang || (targetLang == "da" && lang == "dan") ||
                        (targetLang == "en" && lang == "eng") || (targetLang == "de" && (lang == "deu" || lang == "ger"))
                    val features = voice.features ?: emptySet()
                    val notInstalled = features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    matches && !notInstalled
                }
                _isDanishVoiceInstalled.value = hasInstalledVoice
                hasInstalledVoice
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

    private val _isUsingDanishFallbackVoice = MutableStateFlow(false)
    val isUsingDanishFallbackVoice: StateFlow<Boolean> = _isUsingDanishFallbackVoice.asStateFlow()

    fun createContextForLocale(context: Context, locale: Locale): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Resolves the TTS voice locale to use:
     * 1. Prefer the active app locale voice if installed/supported.
     * 2. If the active app locale voice is unavailable and a Danish voice is available,
     *    fall back to Danish ([DANISH_LOCALE]) and mark [_isDanishVoiceInstalled] = false
     *    so the Profile screen displays the voice fallback warning note.
     * 3. Return null if neither voice is available.
     */
    fun resolveEffectiveTtsLocale(
        context: Context,
        tts: TextToSpeech? = null,
        isLocaleAvailable: (Locale) -> Boolean = { loc -> isTtsLocaleSupported(tts, loc) }
    ): Locale? {
        val targetLocale = resolveTtsLocale(context)
        if (isLocaleAvailable(targetLocale)) {
            _isUsingDanishFallbackVoice.value = false
            _isDanishVoiceInstalled.value = true
            return targetLocale
        }
        if (targetLocale.language.lowercase(Locale.ROOT) != "da" && isLocaleAvailable(DANISH_LOCALE)) {
            _isUsingDanishFallbackVoice.value = true
            _isDanishVoiceInstalled.value = false
            return DANISH_LOCALE
        }
        _isUsingDanishFallbackVoice.value = false
        _isDanishVoiceInstalled.value = false
        return null
    }

    fun formatSpeechCueText(
        context: Context,
        cue: com.example.data.model.TurnCue,
        distanceMeters: Double,
        effectiveVoiceLocale: Locale
    ): String {
        val speechCtx = createContextForLocale(context, effectiveVoiceLocale)
        return com.example.util.routing.TurnCueGenerator.formatCueBannerText(
            cue = cue,
            distanceMeters = distanceMeters,
            context = speechCtx,
            useProvidedContextDirectly = true
        )
    }

    /**
     * Asynchronously probes the device's TextToSpeech engine to check if a voice for the active locale is installed.
     */
    fun checkDanishVoiceAvailability(context: Context, onResult: (Boolean) -> Unit = {}) {
        isDanishVoiceInstalledOverrideForTests?.let { overrideVal ->
            _isDanishVoiceInstalled.value = overrideVal
            onResult(overrideVal)
            return
        }
        val targetLocale = resolveTtsLocale(context)
        var engine: TextToSpeech? = null
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                val installed = if (status == TextToSpeech.SUCCESS) {
                    val effective = resolveEffectiveTtsLocale(context, engine)
                    effective != null && !_isUsingDanishFallbackVoice.value && isTtsLocaleSupported(engine, targetLocale)
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
