package io.github.serabin1.tydl.data

import android.content.Context

/**
 * The Obsidian integration settings. The vault folder is picked once via Android folder access;
 * the filename format and rollover hour are tunable per device.
 */
data class ObsidianSettings(
    val vaultUri: String? = null,
    val filenameFormat: String = DEFAULT_FILENAME_FORMAT,
    val templateUri: String? = null,
    val rolloverHour: Int = DEFAULT_ROLLOVER_HOUR,
) {
    companion object {
        const val DEFAULT_FILENAME_FORMAT = "DD-MM-YYYY"
        const val DEFAULT_ROLLOVER_HOUR = 4

        fun defaults() = ObsidianSettings()
    }
}

/** Persists the Obsidian integration settings. The defaults live in [ObsidianSettings]. */
class ObsidianSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ObsidianSettings = ObsidianSettings(
        vaultUri = prefs.getString(KEY_VAULT_URI, null),
        filenameFormat = prefs.getString(KEY_FILENAME_FORMAT, ObsidianSettings.DEFAULT_FILENAME_FORMAT)
            ?: ObsidianSettings.DEFAULT_FILENAME_FORMAT,
        templateUri = prefs.getString(KEY_TEMPLATE_URI, null),
        rolloverHour = prefs.getInt(KEY_ROLLOVER_HOUR, ObsidianSettings.DEFAULT_ROLLOVER_HOUR),
    )

    fun save(settings: ObsidianSettings) {
        prefs.edit().apply {
            putString(KEY_VAULT_URI, settings.vaultUri)
            putString(KEY_FILENAME_FORMAT, settings.filenameFormat)
            putString(KEY_TEMPLATE_URI, settings.templateUri)
            putInt(KEY_ROLLOVER_HOUR, settings.rolloverHour)
        }.apply()
    }

    private companion object {
        const val PREFS = "tydl_obsidian"
        const val KEY_VAULT_URI = "vault_uri"
        const val KEY_FILENAME_FORMAT = "filename_format"
        const val KEY_TEMPLATE_URI = "template_uri"
        const val KEY_ROLLOVER_HOUR = "rollover_hour"
    }
}
