package io.github.serabin1.tydl.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.log.TydlLog

/** Brings the guard back after a reboot or an app update kills the process. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                TydlLog.i(LogTag.SERVICE, "received ${intent.action}; starting the guard")
                GuardService.start(context)
            }
        }
    }
}
