package io.github.serabin1.tydl

import android.app.Application
import io.github.serabin1.tydl.log.TydlLog

/**
 * Initialises the log sink before any component (guard service, boot receiver, tile) can log.
 * The UI also calls [TydlLog.init] defensively, but this is the one place that always runs first.
 */
class TydlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TydlLog.init(this)
    }
}
