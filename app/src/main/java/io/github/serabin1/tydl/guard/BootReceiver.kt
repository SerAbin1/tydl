package io.github.serabin1.tydl.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the guard back after a reboot or an app update kills the process. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> GuardService.start(context)
        }
    }
}
