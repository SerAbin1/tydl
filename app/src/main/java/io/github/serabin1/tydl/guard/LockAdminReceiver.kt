package io.github.serabin1.tydl.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Enables "I'm done" to lock the screen. The user must activate this in system settings. */
class LockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "Tydl"

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
                Log.w(TAG, "Could not lock screen", e)
                false
            }
        }
    }
}
