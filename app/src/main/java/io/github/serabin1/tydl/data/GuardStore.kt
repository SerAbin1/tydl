package io.github.serabin1.tydl.data

import android.content.Context
import io.github.serabin1.tydl.domain.GuardCodec
import io.github.serabin1.tydl.domain.GuardState

/** Single-key persistence. The codec is pure, so only the two lines of Android remain here. */
class GuardStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): GuardState = GuardCodec.decode(prefs.getString(KEY_STATE, null))

    fun save(state: GuardState) {
        prefs.edit().putString(KEY_STATE, GuardCodec.encode(state)).commit()
    }

    /** Diagnostics only: proves whether unlock broadcasts are reaching the guard. */
    fun loadLastUnlock(): Long = prefs.getLong(KEY_LAST_UNLOCK, 0L)

    fun saveLastUnlock(at: Long) {
        prefs.edit().putLong(KEY_LAST_UNLOCK, at).apply()
    }

    private companion object {
        const val PREFS = "tydl_guard"
        const val KEY_STATE = "state"
        const val KEY_LAST_UNLOCK = "last_unlock"
    }
}
