package io.github.serabin1.tydl.data

import android.content.Context
import io.github.serabin1.tydl.domain.obsidian.TaskGate

/**
 * The Obsidian integration settings plus Tydl behaviour toggles. The tasks folder is picked once
 * via Android folder access; filename format, rollover hour, and task gate are tunable per device.
 *
 * Behaviour toggles (away mode, gravitas, daily stats, sleep) default to on so existing users
 * keep the current experience unless they opt out.
 */
data class ObsidianSettings(
    val vaultUri: String? = null,
    val filenameFormat: String = DEFAULT_FILENAME_FORMAT,
    val templateUri: String? = null,
    val rolloverHour: Int = DEFAULT_ROLLOVER_HOUR,
    val taskGate: TaskGate = DEFAULT_TASK_GATE,
    val awayModeEnabled: Boolean = DEFAULT_AWAY_MODE,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val gravitasEnabled: Boolean = DEFAULT_GRAVITAS,
    val dailyStatsEnabled: Boolean = DEFAULT_DAILY_STATS,
    val sleepTrackingEnabled: Boolean = DEFAULT_SLEEP,
    val dailyStatsAutoDeleteEveryNDays: Int = DEFAULT_AUTO_DELETE_N_DAYS,
    val dailyStatsAutoDeleteSaturday: Boolean = DEFAULT_AUTO_DELETE_SATURDAY,
    val dailyStatsAutoDeleteSunday: Boolean = DEFAULT_AUTO_DELETE_SUNDAY,
) {
    companion object {
        const val DEFAULT_FILENAME_FORMAT = "DD-MM-YYYY"
        const val DEFAULT_ROLLOVER_HOUR = 4
        val DEFAULT_TASK_GATE = TaskGate.HARD

        const val DEFAULT_AWAY_MODE = false
        const val DEFAULT_RADIUS_METERS = 300
        const val DEFAULT_GRAVITAS = true
        const val DEFAULT_DAILY_STATS = true
        const val DEFAULT_SLEEP = true
        const val DEFAULT_AUTO_DELETE_N_DAYS = 0
        const val DEFAULT_AUTO_DELETE_SATURDAY = false
        const val DEFAULT_AUTO_DELETE_SUNDAY = false

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
        taskGate = prefs.getString(KEY_TASK_GATE, ObsidianSettings.DEFAULT_TASK_GATE.name)
            ?.let { runCatching { TaskGate.valueOf(it) }.getOrNull() }
            ?: ObsidianSettings.DEFAULT_TASK_GATE,
        awayModeEnabled = prefs.getBoolean(KEY_AWAY_MODE, ObsidianSettings.DEFAULT_AWAY_MODE),
        homeLatitude = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull(),
        homeLongitude = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull(),
        radiusMeters = prefs.getInt(KEY_RADIUS, ObsidianSettings.DEFAULT_RADIUS_METERS),
        gravitasEnabled = prefs.getBoolean(KEY_GRAVITAS, ObsidianSettings.DEFAULT_GRAVITAS),
        dailyStatsEnabled = prefs.getBoolean(KEY_DAILY_STATS, ObsidianSettings.DEFAULT_DAILY_STATS),
        sleepTrackingEnabled = prefs.getBoolean(KEY_SLEEP, ObsidianSettings.DEFAULT_SLEEP),
        dailyStatsAutoDeleteEveryNDays = prefs.getInt(KEY_AUTO_DELETE_N_DAYS, ObsidianSettings.DEFAULT_AUTO_DELETE_N_DAYS),
        dailyStatsAutoDeleteSaturday = prefs.getBoolean(KEY_AUTO_DELETE_SATURDAY, ObsidianSettings.DEFAULT_AUTO_DELETE_SATURDAY),
        dailyStatsAutoDeleteSunday = prefs.getBoolean(KEY_AUTO_DELETE_SUNDAY, ObsidianSettings.DEFAULT_AUTO_DELETE_SUNDAY),
    )

    fun save(settings: ObsidianSettings) {
        prefs.edit().apply {
            putString(KEY_VAULT_URI, settings.vaultUri)
            putString(KEY_FILENAME_FORMAT, settings.filenameFormat)
            putString(KEY_TEMPLATE_URI, settings.templateUri)
            putInt(KEY_ROLLOVER_HOUR, settings.rolloverHour)
            putString(KEY_TASK_GATE, settings.taskGate.name)
            putBoolean(KEY_AWAY_MODE, settings.awayModeEnabled)
            putString(KEY_HOME_LAT, settings.homeLatitude?.toString())
            putString(KEY_HOME_LON, settings.homeLongitude?.toString())
            putInt(KEY_RADIUS, settings.radiusMeters)
            putBoolean(KEY_GRAVITAS, settings.gravitasEnabled)
            putBoolean(KEY_DAILY_STATS, settings.dailyStatsEnabled)
            putBoolean(KEY_SLEEP, settings.sleepTrackingEnabled)
            putInt(KEY_AUTO_DELETE_N_DAYS, settings.dailyStatsAutoDeleteEveryNDays)
            putBoolean(KEY_AUTO_DELETE_SATURDAY, settings.dailyStatsAutoDeleteSaturday)
            putBoolean(KEY_AUTO_DELETE_SUNDAY, settings.dailyStatsAutoDeleteSunday)
        }.apply()
    }

    private companion object {
        const val PREFS = "tydl_obsidian"
        const val KEY_VAULT_URI = "vault_uri"
        const val KEY_FILENAME_FORMAT = "filename_format"
        const val KEY_TEMPLATE_URI = "template_uri"
        const val KEY_ROLLOVER_HOUR = "rollover_hour"
        const val KEY_TASK_GATE = "task_gate"
        const val KEY_AWAY_MODE = "away_mode"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LON = "home_lon"
        const val KEY_RADIUS = "radius_meters"
        const val KEY_GRAVITAS = "gravitas"
        const val KEY_DAILY_STATS = "daily_stats"
        const val KEY_SLEEP = "sleep_tracking"
        const val KEY_AUTO_DELETE_N_DAYS = "auto_delete_days"
        const val KEY_AUTO_DELETE_SATURDAY = "auto_delete_saturday"
        const val KEY_AUTO_DELETE_SUNDAY = "auto_delete_sunday"
    }
}
