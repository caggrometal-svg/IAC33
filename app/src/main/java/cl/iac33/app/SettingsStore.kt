package cl.iac33.app

import android.content.Context

data class AppSettings(
    val aiLocalFirst: Boolean = false,
    val seismicAutoRefresh: Boolean = true,
    val seismicMinimumMagnitude: Float = 3.0f,
    val mapZoom: Int = 5,
    val haptics: Boolean = true,
    val accentColorHex: String = DEFAULT_ACCENT
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val storedAccent = prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        val accent = normalizeAccent(storedAccent)
        return AppSettings(
            aiLocalFirst = prefs.getBoolean(KEY_AI_LOCAL_FIRST, false),
            seismicAutoRefresh = prefs.getBoolean(KEY_SEISMIC_AUTO_REFRESH, true),
            seismicMinimumMagnitude = prefs.getFloat(KEY_SEISMIC_MIN_MAG, 3.0f),
            mapZoom = prefs.getInt(KEY_MAP_ZOOM, 5).coerceIn(3, 8),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            accentColorHex = accent
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AI_LOCAL_FIRST, settings.aiLocalFirst)
            .putBoolean(KEY_SEISMIC_AUTO_REFRESH, settings.seismicAutoRefresh)
            .putFloat(KEY_SEISMIC_MIN_MAG, settings.seismicMinimumMagnitude)
            .putInt(KEY_MAP_ZOOM, settings.mapZoom.coerceIn(3, 8))
            .putBoolean(KEY_HAPTICS, settings.haptics)
            .putString(KEY_ACCENT_COLOR, normalizeAccent(settings.accentColorHex))
            .apply()
    }

    fun reset() = save(AppSettings())

    companion object {
        private const val PREFS = "iac33_settings"
        private const val KEY_AI_LOCAL_FIRST = "ai_local_first"
        private const val KEY_SEISMIC_AUTO_REFRESH = "seismic_auto_refresh"
        private const val KEY_SEISMIC_MIN_MAG = "seismic_min_magnitude"
        private const val KEY_MAP_ZOOM = "map_zoom"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_ACCENT_COLOR = "accent_color_hex"
        private const val DEFAULT_ACCENT = "#4F46E5"

        private fun normalizeAccent(value: String): String {
            val candidate = value.trim().uppercase()
            return if (Regex("^#[0-9A-F]{6}$").matches(candidate)) candidate else DEFAULT_ACCENT
        }
    }
}
