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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.serabin1.tydl.data.GuardStore
import io.github.serabin1.tydl.domain.DoneOutcome
import io.github.serabin1.tydl.domain.GuardEffect
import io.github.serabin1.tydl.domain.GuardEngine
import io.github.serabin1.tydl.domain.GuardEvent
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.ExpiryScreen
import io.github.serabin1.tydl.ui.MainActivity
import io.github.serabin1.tydl.ui.PromptScreen
import io.github.serabin1.tydl.ui.theme.TydlTheme
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
 * this class is the imperative shell: broadcasts in, overlays out.
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: GuardStore
    private lateinit var keyguard: KeyguardManager
    private var state = GuardState()

    /** Debounces SCREEN_ON + USER_PRESENT arriving for the same unlock. */
    private var lastUnlockAt = 0L
    private var promptOverlay: OverlayWindow? = null
    private var expiryOverlay: OverlayWindow? = null

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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        scope.cancel()
        hidePrompt()
        hideExpiry()
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
        val now = now()
        if (state.session != null) hidePrompt()
        if (state.session?.isOverrun(now) != true) hideExpiry()
    }

    private fun showPrompt() = whenUnlocked("prompt") {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "prompt: no overlay permission, using notification")
            notifyAlert(
                title = "State your reason",
                text = "Tap to tell Tydl why you unlocked.",
                extra = EXTRA_SHOW_PROMPT,
            )
        } else {
            hidePrompt()
            promptOverlay = showOverlay { overlay ->
                TydlTheme {
                    PromptScreen(onStart = { reason, minutes ->
                        startSession(this@GuardService, reason, minutes)
                        overlay.remove()
                        promptOverlay = null
                    })
                }
            }
            Log.i(TAG, "prompt overlay shown")
        }
    }

    private fun showExpiry() = whenUnlocked("expiry") {
        val session = state.session ?: return@whenUnlocked
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "expiry: no overlay permission, using notification")
            notifyAlert(
                title = "Time's up",
                text = "\"${session.reason}\" is past its minutes.",
                extra = EXTRA_SHOW_EXPIRY,
            )
        } else {
            hideExpiry()
            expiryOverlay = showOverlay { overlay ->
                TydlTheme {
                    ExpiryScreen(
                        session = session,
                        now = now(),
                        onDone = {
                            endAndLock(this@GuardService)
                            overlay.remove()
                            expiryOverlay = null
                        },
                        onExtend = {
                            extend(this@GuardService)
                            overlay.remove()
                            expiryOverlay = null
                        },
                    )
                }
            }
            Log.i(TAG, "expiry overlay shown")
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

    private fun showOverlay(content: @androidx.compose.runtime.Composable (OverlayWindow) -> Unit): OverlayWindow {
        val wm = getSystemService(WindowManager::class.java)
        val overlay = OverlayWindow(this, wm)
        overlay.setContent(content)
        overlay.show()
        return overlay
    }

    private fun hidePrompt() {
        promptOverlay?.remove()
        promptOverlay = null
    }

    private fun hideExpiry() {
        expiryOverlay?.remove()
        expiryOverlay = null
    }

    private fun notifyGuard() {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(GuardNotifications.ID_GUARD, GuardNotifications.guard(this, state))
    }

    private fun notifyAlert(title: String, text: String, extra: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(
                GuardNotifications.ID_ALERT,
                GuardNotifications.alert(this, title, text, MainActivity::class.java, extra),
            )
    }

    private fun now(): Long = System.currentTimeMillis()

    private class OverlayWindow(
        private val context: Context,
        private val wm: WindowManager,
    ) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)
        private val view = ComposeView(context)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        init {
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            view.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }

        fun setContent(content: @androidx.compose.runtime.Composable (OverlayWindow) -> Unit) {
            view.setContent { content(this) }
        }

        fun show() {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            wm.addView(view, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun remove() {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                wm.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove overlay", e)
            } finally {
                store.clear()
            }
        }
    }

    companion object {
        private const val TAG = "Tydl"
        private const val ACTION_START = "io.github.serabin1.tydl.action.START"
        private const val ACTION_END = "io.github.serabin1.tydl.action.END"
        private const val ACTION_EXTEND = "io.github.serabin1.tydl.action.EXTEND"
        private const val ACTION_DISMISS = "io.github.serabin1.tydl.action.DISMISS"
        private const val ACTION_PAUSE = "io.github.serabin1.tydl.action.PAUSE"
        private const val ACTION_UNLOCK = "io.github.serabin1.tydl.action.UNLOCK"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_UNTIL = "until"
        const val EXTRA_SHOW_PROMPT = "show_prompt"
        const val EXTRA_SHOW_EXPIRY = "show_expiry"
        private const val UNLOCK_DEBOUNCE_MS = 1_500L
        private const val KEYGUARD_RETRY_MS = 600L
        private const val MAX_UNLOCK_ATTEMPTS = 3

        fun start(context: Context) = send(context, Intent(context, GuardService::class.java))

        fun startSession(context: Context, reason: String, minutes: Int) = send(
            context,
            base(context)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MINUTES, minutes),
        )

        fun end(context: Context) = send(context, base(context).setAction(ACTION_END))

        /** Ends the session and warns if the screen could not be locked. */
        fun endAndLock(context: Context) {
            end(context)
            val warning = DoneOutcome.warning(LockAdminReceiver.lockScreen(context))
            if (warning != null) {
                GuardNotifications.warn(context, "Couldn't lock the screen", warning)
            }
        }

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
