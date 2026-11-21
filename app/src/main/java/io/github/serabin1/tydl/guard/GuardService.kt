package io.github.serabin1.tydl.guard

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import io.github.serabin1.tydl.data.GuardStore
import io.github.serabin1.tydl.domain.GuardEffect
import io.github.serabin1.tydl.domain.GuardEngine
import io.github.serabin1.tydl.domain.GuardEvent
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.ExpiryActivity
import io.github.serabin1.tydl.ui.PromptActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the only state that matters and turns [GuardEvent]s into screens.
 *
 * It runs as a foreground service so it survives app switches and process eviction, keeps a
 * persistent status notification, and reacts to unlocks. All policy lives in [GuardEngine];
 * this class is the imperative shell: broadcasts in, activities out.
 *
 * Since Android 14, a background activity launch needs more than the overlay permission: the
 * app must have a currently visible non-activity window, or the launch is silently blocked
 * ([launch] bridges this with a 1x1 transparent overlay before starting the activity).
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: GuardStore
    private lateinit var keyguard: KeyguardManager
    private var state = GuardState()

    /** Debounces SCREEN_ON + USER_PRESENT arriving for the same unlock. */
    private var lastUnlockAt = 0L
    private var bridgeView: View? = null
    private var lastUiShownAt = 0L

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "USER_PRESENT (keyguardLocked=${keyguard.isKeyguardLocked})")
                    onUnlock()
                }

                Intent.ACTION_SCREEN_ON -> {
                    val locked = keyguard.isKeyguardLocked
                    Log.i(TAG, "SCREEN_ON (keyguardLocked=$locked)")
                    if (!locked) {
                        onUnlock()
                    } else {
                        // The keyguard can be dismissed a beat after the screen comes on.
                        scope.launch {
                            delay(400)
                            if (!keyguard.isKeyguardLocked) onUnlock()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = GuardStore(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        state = store.load()
        Log.i(
            TAG,
            "service created; session=${state.session?.reason ?: "none"} " +
                "expiryShownAt=${state.expiryShownAt}",
        )
        GuardNotifications.ensureChannels(this)
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startForeground(
            GuardNotifications.ID_GUARD,
            GuardNotifications.guard(this, state),
        )
        Log.i(TAG, "foreground started")
        scope.launch {
            while (isActive) {
                delay(1_000)
                handle(GuardEvent.Tick(now()))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_STICKY
        when (command.action) {
            ACTION_START -> handle(
                GuardEvent.Start(
                    reason = command.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "Unspecified" },
                    minutes = command.getIntExtra(EXTRA_MINUTES, Session.DEFAULT_MINUTES),
                    now = now(),
                ),
            )

            ACTION_END -> handle(GuardEvent.End)
            ACTION_EXTEND -> handle(GuardEvent.Extend(now()))
            ACTION_DISMISS -> handle(GuardEvent.Dismissed(now()))
            ACTION_PAUSE -> handle(GuardEvent.Paused(command.getLongExtra(EXTRA_UNTIL, 0L)))
            ACTION_UNLOCK -> {
                Log.i(TAG, "manual unlock requested")
                onUnlock()
            }

            ACTION_SHOWN -> {
                lastUiShownAt = now()
                Log.i(TAG, "ui shown handshake received")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        scope.cancel()
        removeOverlayBridge()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver already gone", e)
        }
        super.onDestroy()
    }

    private fun onUnlock() {
        val at = now()
        if (at - lastUnlockAt < UNLOCK_DEBOUNCE_MS) {
            Log.i(TAG, "unlock debounced (${at - lastUnlockAt} ms)")
            return
        }
        lastUnlockAt = at
        store.saveLastUnlock(at)
        handle(GuardEvent.Unlocked(at))
    }

    private fun handle(event: GuardEvent) {
        val step = GuardEngine.reduce(state, event)
        if (step.state != state) {
            state = step.state
            store.save(state)
            notifyGuard()
        }
        Log.i(
            TAG,
            "event=${event::class.simpleName} effect=${step.effect::class.simpleName} " +
                "session=${state.session?.reason ?: "none"}",
        )
        when (step.effect) {
            GuardEffect.ShowPrompt -> showPrompt()
            GuardEffect.ShowExpiry -> showExpiry()
            GuardEffect.None -> Unit
        }
    }

    private fun showPrompt() = whenUnlocked("prompt") {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "prompt: no overlay permission, using notification")
            notifyAlert(
                title = "State your reason",
                text = "Tap to tell Tydl why you unlocked.",
                target = PromptActivity::class.java,
            )
        } else {
            launch(
                target = PromptActivity::class.java,
                launchedAt = now(),
                fallbackText = "State your reason — tap to continue.",
            )
        }
    }

    private fun showExpiry() = whenUnlocked("expiry") {
        val session = state.session ?: return@whenUnlocked
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "expiry: no overlay permission, using notification")
            notifyAlert(
                title = "Time's up",
                text = "\"${session.reason}\" is past its minutes.",
                target = ExpiryActivity::class.java,
            )
        } else {
            launch(
                target = ExpiryActivity::class.java,
                launchedAt = now(),
                fallbackText = "\u201C${session.reason}\u201D is past its minutes — tap to finish.",
            )
        }
    }

    /**
     * On biometric unlock the keyguard flag can lag behind USER_PRESENT, so retry briefly
     * instead of silently dropping the prompt.
     */
    private fun whenUnlocked(label: String, attempt: Int = 0, block: () -> Unit) {
        if (!keyguard.isKeyguardLocked) {
            block()
            return
        }
        Log.i(TAG, "$label: keyguard still locked (attempt $attempt)")
        if (attempt < MAX_UNLOCK_ATTEMPTS) {
            scope.launch {
                delay(KEYGUARD_RETRY_MS)
                whenUnlocked(label, attempt + 1, block)
            }
        }
    }

    private fun launch(target: Class<*>, launchedAt: Long, fallbackText: String) {
        val intent = Intent(this, target).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val windowManager = getSystemService(WindowManager::class.java)
        val bridgeAdded = windowManager?.let { addOverlayBridge(it) } == true

        if (bridgeAdded) {
            scope.launch {
                delay(BRIDGE_LAUNCH_DELAY_MS)
                doStartActivity(intent, target)
                Log.i(TAG, "overlay bridge will be removed soon")
                delay(BRIDGE_REMOVE_DELAY_MS)
                removeOverlayBridge()
            }
        } else {
            doStartActivity(intent, target)
        }

        scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (lastUiShownAt < launchedAt) {
                Log.w(TAG, "$target was not shown within $HANDSHAKE_TIMEOUT_MS ms; posting a notification")
                notifyAlert("Tydl", fallbackText, target)
            }
        }
    }

    private fun doStartActivity(intent: Intent, target: Class<*>) {
        try {
            Log.i(TAG, "startActivity $target")
            startActivity(intent)
            Log.i(TAG, "startActivity returned for $target")
        } catch (e: Exception) {
            Log.w(TAG, "Background launch of $target refused", e)
            notifyAlert("Tydl needs you", "Tap to continue.", target)
        }
    }

    private fun addOverlayBridge(windowManager: WindowManager): Boolean {
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return try {
            windowManager.addView(view, params)
            bridgeView = view
            Log.i(TAG, "overlay bridge added")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not add the overlay bridge", e)
            false
        }
    }

    private fun removeOverlayBridge() {
        bridgeView?.let {
            try {
                getSystemService(WindowManager::class.java)?.removeView(it)
                Log.i(TAG, "overlay bridge removed")
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the overlay bridge", e)
            }
        }
        bridgeView = null
    }

    private fun notifyGuard() {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(GuardNotifications.ID_GUARD, GuardNotifications.guard(this, state))
    }

    private fun notifyAlert(title: String, text: String, target: Class<*>) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(GuardNotifications.ID_ALERT, GuardNotifications.alert(this, title, text, target))
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "Tydl"
        private const val ACTION_START = "io.github.serabin1.tydl.action.START"
        private const val ACTION_END = "io.github.serabin1.tydl.action.END"
        private const val ACTION_EXTEND = "io.github.serabin1.tydl.action.EXTEND"
        private const val ACTION_DISMISS = "io.github.serabin1.tydl.action.DISMISS"
        private const val ACTION_PAUSE = "io.github.serabin1.tydl.action.PAUSE"
        private const val ACTION_UNLOCK = "io.github.serabin1.tydl.action.UNLOCK"
        private const val ACTION_SHOWN = "io.github.serabin1.tydl.action.SHOWN"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_UNTIL = "until"
        private const val UNLOCK_DEBOUNCE_MS = 1_500L
        private const val KEYGUARD_RETRY_MS = 600L
        private const val MAX_UNLOCK_ATTEMPTS = 3
        private const val BRIDGE_LAUNCH_DELAY_MS = 150L
        private const val BRIDGE_REMOVE_DELAY_MS = 1_500L
        private const val HANDSHAKE_TIMEOUT_MS = 2_000L

        fun start(context: Context) = send(context, Intent(context, GuardService::class.java))

        fun startSession(context: Context, reason: String, minutes: Int) = send(
            context,
            base(context)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MINUTES, minutes),
        )

        fun end(context: Context) = send(context, base(context).setAction(ACTION_END))

        fun extend(context: Context) = send(context, base(context).setAction(ACTION_EXTEND))

        fun dismiss(context: Context) = send(context, base(context).setAction(ACTION_DISMISS))

        fun pause(context: Context, minutes: Int) = send(
            context,
            base(context).setAction(ACTION_PAUSE).putExtra(
                EXTRA_UNTIL,
                System.currentTimeMillis() + minutes * 60_000L,
            ),
        )

        fun resume(context: Context) = send(
            context,
            base(context).setAction(ACTION_PAUSE).putExtra(EXTRA_UNTIL, 0L),
        )

        /** Diagnostics: run the unlock path without locking the phone. */
        fun simulateUnlock(context: Context) = send(
            context,
            base(context).setAction(ACTION_UNLOCK),
        )

        /** Called by a screen when it becomes visible, so the service can detect silent drops. */
        fun markShown(context: Context) = send(context, base(context).setAction(ACTION_SHOWN))

        private fun base(context: Context) = Intent(context, GuardService::class.java)

        private fun send(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the guard", e)
            }
        }
    }
}
