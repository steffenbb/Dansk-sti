package com.example.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppLanguageOption(val languageTag: String) {
    SYSTEM("system"),
    DANISH("da"),
    ENGLISH("en"),
    GERMAN("de");

    companion object {
        fun fromTag(tag: String?): AppLanguageOption = when (tag?.trim()?.lowercase(Locale.ROOT)) {
            "da", "da-dk" -> DANISH
            "en", "en-us", "en-gb" -> ENGLISH
            "de", "de-de" -> GERMAN
            else -> SYSTEM
        }
    }
}

object AppLocaleHelper {
    val DEFAULT_DANISH_LOCALE: Locale = Locale("da", "DK")

    fun ensureDefaultLocaleIfUnconfigured(context: Context) {
        val currentDefault = Locale.getDefault()
        // If JVM/system default is generic English (e.g. en_US in JVM test runners) and no per-app locale was chosen,
        // ensure the app's base Danish locale is active so resources resolve to default values/strings.xml (Danish).
        var hasExplicitAppLocale = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                val appLocales = lm?.applicationLocales
                if (appLocales != null && !appLocales.isEmpty) {
                    hasExplicitAppLocale = true
                }
            }
        }
        val isDefaultEnUs = currentDefault.language == "en" && currentDefault.country == "US"
        val isCountrylessDa = currentDefault.language == "da" && currentDefault.country.isEmpty()
        if (!hasExplicitAppLocale && (isDefaultEnUs || isCountrylessDa)) {
            Locale.setDefault(DEFAULT_DANISH_LOCALE)
            runCatching {
                val res = context.resources
                val config = Configuration(res.configuration)
                config.setLocale(DEFAULT_DANISH_LOCALE)
                config.setLocales(LocaleList(DEFAULT_DANISH_LOCALE))
                @Suppress("DEPRECATION")
                res.updateConfiguration(config, res.displayMetrics)
            }
        }
    }

    fun getAppLocale(context: Context?): Locale {
        if (context == null) return DEFAULT_DANISH_LOCALE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                val appLocales = lm?.applicationLocales
                if (appLocales != null && !appLocales.isEmpty) {
                    appLocales[0]?.let { return it }
                }
            }
        }
        val configLocales = context.resources?.configuration?.locales
        if (configLocales != null && !configLocales.isEmpty) {
            configLocales[0]?.let { loc ->
                if (loc.language == "en" && loc.country == "US") {
                    return DEFAULT_DANISH_LOCALE
                }
                return loc
            }
        }
        val def = Locale.getDefault()
        if (def != null && !(def.language == "en" && def.country == "US")) {
            return def
        }
        return DEFAULT_DANISH_LOCALE
    }

    fun getCurrentLocale(context: Context?): Locale = getAppLocale(context)

    fun configurationLocale(context: Context?): Locale = getAppLocale(context)

    /**
     * Returns a Context whose resources Configuration is set to the app's active locale
     * (useful in Services, BroadcastReceivers/AppWidgetProviders, and TTS).
     */
    fun getLocalizedContext(context: Context): Context {
        val targetLocale = getAppLocale(context)
        val currentConfig = context.resources?.configuration ?: return context
        val currentPrimary = if (!currentConfig.locales.isEmpty) currentConfig.locales[0] else null
        if (currentPrimary != null && currentPrimary.language == targetLocale.language) {
            return context
        }
        val newConfig = Configuration(currentConfig)
        newConfig.setLocales(LocaleList(targetLocale))
        return context.createConfigurationContext(newConfig) ?: context
    }

    fun createLocalizedContext(context: Context): Context = getLocalizedContext(context)

    /**
     * Sets the per-app locale on Android 13+ via system LocaleManager.
     * Passing null or empty tag resets to follow system language.
     */
    fun setApplicationLanguageTag(context: Context, languageTag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                if (languageTag.isNullOrBlank() || languageTag == "system") {
                    lm?.applicationLocales = LocaleList.getEmptyLocaleList()
                } else {
                    val locales = LocaleList.forLanguageTags(languageTag)
                    lm?.applicationLocales = locales
                    if (!locales.isEmpty) {
                        val chosen = locales[0]
                        val res = context.resources
                        val config = Configuration(res.configuration)
                        config.setLocale(chosen)
                        config.setLocales(LocaleList(chosen))
                        @Suppress("DEPRECATION")
                        res.updateConfiguration(config, res.displayMetrics)
                    }
                }
            }
        }
    }

    fun setApplicationLanguage(context: Context, option: AppLanguageOption) {
        setApplicationLanguageTag(context, option.languageTag)
    }

    /**
     * Gets the current per-app language tag on Android 13+ ("da", "en", "de", or "system" if following phone).
     */
    fun getCurrentApplicationLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                val locales = lm?.applicationLocales
                if (locales == null || locales.isEmpty) {
                    return "system"
                }
                val lang = locales[0]?.language ?: return "system"
                return when (lang) {
                    "da" -> "da"
                    "en" -> "en"
                    "de" -> "de"
                    else -> lang
                }
            }
        }
        return "system"
    }

    fun getCurrentLanguageOption(context: Context): AppLanguageOption {
        return AppLanguageOption.fromTag(getCurrentApplicationLanguageTag(context))
    }

    fun formatNumber(context: Context?, pattern: String, vararg args: Any?): String {
        val locale = getAppLocale(context)
        return String.format(locale, pattern, *args)
    }

    fun formatNumber(locale: Locale, pattern: String, vararg args: Any?): String {
        return String.format(locale, pattern, *args)
    }

    fun formatDecimal(value: Double, decimals: Int, locale: Locale = DEFAULT_DANISH_LOCALE): String {
        return String.format(locale, "%.${decimals}f", value)
    }

    fun formatWeightKg(totalGrams: Int, locale: Locale = DEFAULT_DANISH_LOCALE): String {
        val kg = totalGrams / 1000.0
        return String.format(locale, "%.1f", kg)
    }

    fun formatInteger(value: Int, locale: Locale = DEFAULT_DANISH_LOCALE): String {
        return NumberFormat.getIntegerInstance(locale).format(value)
    }

    fun formatShortDate(epochMillis: Long, locale: Locale = DEFAULT_DANISH_LOCALE): String {
        val safeMillis = if (epochMillis > 0L) epochMillis else System.currentTimeMillis()
        val pattern = when (locale.language) {
            "en" -> "d MMM yyyy"
            else -> "d. MMM yyyy"
        }
        return SimpleDateFormat(pattern, locale).format(Date(safeMillis))
    }
}

fun Context.appLocale(): Locale = AppLocaleHelper.getAppLocale(this)

