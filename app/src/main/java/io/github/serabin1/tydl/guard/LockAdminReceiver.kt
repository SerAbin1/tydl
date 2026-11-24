package io.github.serabin1.tydl.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.log.TydlLog

/** Enables "I'm done" to lock the screen. The user must activate this in system settings. */
class LockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        TydlLog.i(LogTag.SERVICE, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        TydlLog.i(LogTag.SERVICE, "device admin disabled")
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, LockAdminReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)
                .isAdminActive(componentName(context))

        fun lockScreen(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = componentName(context)
            if (!dpm.isAdminActive(admin)) return false
            return try {
                dpm.lockNow()
                true
            } catch (e: SecurityException) {
                TydlLog.w(LogTag.ERROR, "could not lock the screen", e)
                false
            }
        }
    }
}
