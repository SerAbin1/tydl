package io.github.serabin1.tydl.guard

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
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
import io.github.serabin1.tydl.data.ObsidianVault
import io.github.serabin1.tydl.domain.DoneOutcome
import io.github.serabin1.tydl.domain.GuardEffect
import io.github.serabin1.tydl.domain.GuardEngine
import io.github.serabin1.tydl.domain.GuardEvent
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import io.github.serabin1.tydl.domain.obsidian.ObsidianTask
import io.github.serabin1.tydl.ui.DoneScreen
import io.github.serabin1.tydl.ui.ExpiryScreen
import io.github.serabin1.tydl.ui.MainActivity
import io.github.serabin1.tydl.ui.PromptScreen
import io.github.serabin1.tydl.ui.TasksScreen
import io.github.serabin1.tydl.ui.theme.TydlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private lateinit var power: PowerManager
    private var state = GuardState()

    /** Debounces SCREEN_ON + USER_PRESENT arriving for the same unlock. */
    private var lastUnlockAt = 0L
    private var promptOverlay: OverlayWindow? = null
    private var expiryOverlay: OverlayWindow? = null
    private var doneOverlay: OverlayWindow? = null
    private var tasksOverlay: OverlayWindow? = null
    private var keyguardPollJob: Job? = null

    /** Tasks picked from the vault for the current prompt, if the vault is configured. */
    private var suggestedTasks: List<ObsidianTask> = emptyList()
    /** A task the user tapped; pre-fills reason but keeps friction (time still required). */
    private var pendingTask: ObsidianTask? = null

    /** Local day key of the last rollover performed; rollover runs once per day. */
    private var lastRolloverDayKey: String? = null

    private lateinit var vault: ObsidianVault

    /** Interrupts the deadline loop when something other than the clock changes what to wait for. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "USER_PRESENT (keyguardLocked=${keyguard.isKeyguardLocked})")
                    cancelKeyguardPoll("user present")
                    onUnlock()
                }

                Intent.ACTION_SCREEN_ON -> {
                    val locked = keyguard.isKeyguardLocked
                    Log.i(TAG, "SCREEN_ON (keyguardLocked=$locked)")
                    maybeRollover(now())
                    if (!locked) {
                        cancelKeyguardPoll("screen on unlocked")
                        onUnlock()
                    } else {
                        startKeyguardPoll()
                    }
                    // Whether a deadline can be shown at all depends on the screen and keyguard.
                    wakeSignal.trySend(Unit)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "SCREEN_OFF (keyguardLocked=${keyguard.isKeyguardLocked})")
                    cancelKeyguardPoll("screen off")
                    handle(GuardEvent.ScreenOff(now()))
                    wakeSignal.trySend(Unit)
                }

                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    Log.i(TAG, "${intent.action}: clock changed")
                    wakeSignal.trySend(Unit)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = GuardStore(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        vault = ObsidianVault(this)
        state = store.load()
        Log.i(TAG, "service created; session=${state.session?.reason ?: "none"}")
        GuardNotifications.ensureChannels(this)
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startForeground(
            GuardNotifications.ID_GUARD,
            GuardNotifications.guard(this, state),
        )
        Log.i(TAG, "foreground started")
        startDeadlineLoop()
    }

    /**
     * One coroutine for the service's whole life. It sleeps until the next deadline the domain
     * named, or until a state change makes that deadline wrong — whichever comes first. There is
     * no heartbeat: with nothing pending it blocks on [wakeSignal] instead of waking on a timer.
     */
    private fun startDeadlineLoop() {
        scope.launch {
            while (isActive) {
                val at = nextWake()
                if (at == null) {
                    Log.i(TAG, "no deadline pending; waiting for a state change")
                    wakeSignal.receive()
                    continue
                }
                val wait = (at - now()).coerceAtLeast(0L)
                Log.i(TAG, "next deadline in $wait ms")
                val signalled = withTimeoutOrNull(wait) { wakeSignal.receive() }
                if (signalled == null) {
                    Log.i(TAG, "deadline reached")
                    handle(GuardEvent.DeadlineReached(now()))
                }
            }
        }
    }

    /**
     * A deadline is only worth waking for when the expiry can actually be drawn: the overlay
     * cannot appear over the keyguard, and the unlock broadcast covers the overdue case anyway.
     */
    private fun nextWake(): Long? {
        val canShow = power.isInteractive && !keyguard.isKeyguardLocked
        return if (canShow) GuardEngine.nextWakeAt(state, now()) else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_STICKY
        when (command.action) {
            ACTION_START -> handle(
                GuardEvent.Start(
                    reason = command.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "Unspecified" },
                    minutes = command.getIntExtra(EXTRA_MINUTES, Session.DEFAULT_MINUTES),
                    now = now(),
                    task = command.getStringExtra(EXTRA_TASK),
                ),
            )

            ACTION_END -> handle(GuardEvent.End)
            ACTION_EXTEND -> handle(
                GuardEvent.Extend(
                    now = now(),
                    minutes = command.getIntExtra(EXTRA_MINUTES, Session.EXTENSION_MINUTES),
                ),
            )
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
        cancelKeyguardPoll("destroy")
        scope.cancel()
        hidePrompt()
        hideExpiry()
        hideDone()
        hideTasks()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver already gone", e)
        }
        super.onDestroy()
    }

    private fun onUnlock() {
        // The keyguard coming down changes whether a deadline can be shown, even when the
        // unlock itself changes no state (a live session, for instance).
        wakeSignal.trySend(Unit)
        val at = now()
        if (at - lastUnlockAt < UNLOCK_DEBOUNCE_MS) {
            Log.i(TAG, "unlock debounced (${at - lastUnlockAt} ms)")
            return
        }
        lastUnlockAt = at
        store.saveLastUnlock(at)
        handle(GuardEvent.Unlocked(at))
    }

    /** Polls the keyguard after a locked SCREEN_ON until it unlocks or times out. */
    private fun startKeyguardPoll() {
        cancelKeyguardPoll("restart")
        keyguardPollJob = scope.launch {
            Log.i(TAG, "keyguard poll started")
            val deadline = now() + KEYGUARD_POLL_TIMEOUT_MS
            while (isActive && now() < deadline) {
                delay(KEYGUARD_POLL_INTERVAL_MS)
                if (!keyguard.isKeyguardLocked) {
                    Log.i(TAG, "keyguard poll success")
                    onUnlock()
                    return@launch
                }
                if (!power.isInteractive) {
                    Log.i(TAG, "keyguard poll stopped: screen off")
                    return@launch
                }
            }
            Log.i(TAG, "keyguard poll timeout")
        }
    }

    private fun cancelKeyguardPoll(reason: String) {
        keyguardPollJob?.let {
            if (it.isActive) {
                it.cancel()
                Log.i(TAG, "keyguard poll cancelled: $reason")
            }
        }
        keyguardPollJob = null
    }

    private fun handle(event: GuardEvent) {
        val step = GuardEngine.reduce(state, event)
        val changed = step.state != state
        if (changed) {
            state = step.state
            store.save(state)
        }
        // Cheap and silent, and it keeps a paused guard from claiming to be paused after it ends.
        notifyGuard()
        Log.i(
            TAG,
            "event=${event::class.simpleName} effect=${step.effect::class.simpleName} " +
                "session=${state.session?.reason ?: "none"}",
        )
        when (step.effect) {
            GuardEffect.ShowPrompt -> showPrompt()
            GuardEffect.ShowExpiry -> showExpiry()
            GuardEffect.ShowDone -> showDone()
            GuardEffect.None -> Unit
        }
        val now = now()
        if (state.session != null) hidePrompt()
        if (state.session?.isOverrun(now) != true) hideExpiry()
        if (state.completedSession == null) hideDone()
        if (changed) wakeSignal.trySend(Unit)
    }

    private fun showPrompt() {
        whenUnlocked("prompt") {
            scope.launch {
                suggestedTasks = withContext(Dispatchers.IO) { loadSuggestedTasks() }
                if (suggestedTasks.isNotEmpty() && pendingTask == null) {
                    showTasks(suggestedTasks) { task ->
                        suggestedTasks = emptyList()
                        pendingTask = task
                        showPrompt()
                    }
                    return@launch
                }
                showPromptForm()
            }
        }
    }

    private fun showPromptForm() {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "prompt: no overlay permission, using notification")
            notifyAlert(
                title = "State your reason",
                text = "Tap to tell Tydl why you unlocked.",
                extra = EXTRA_SHOW_PROMPT,
            )
        } else {
            hidePrompt()
            val initialReason = pendingTask?.let { ObsidianNotes.display(it) } ?: ""
            val taskText = pendingTask?.text
            promptOverlay = showOverlay { overlay ->
                TydlTheme {
                    PromptScreen(initialReason = initialReason, onStart = { reason, minutes ->
                        startSession(this@GuardService, reason, minutes, taskText)
                        pendingTask = null
                        overlay.remove()
                        promptOverlay = null
                    })
                }
            }
            Log.i(TAG, "prompt overlay shown")
        }
    }

    private fun loadSuggestedTasks(): List<ObsidianTask> {
        if (!vault.isConfigured()) {
            Log.i(TAG, "tasks: vault not configured")
            return emptyList()
        }
        val today = vault.todayFileName()
        if (!vault.hasToday()) {
            Log.i(TAG, "tasks: no note for $today")
            return emptyList()
        }
        val content = vault.readToday() ?: run {
            Log.w(TAG, "tasks: failed to read $today")
            return emptyList()
        }
        val suggestions = ObsidianNotes.suggestions(
            ObsidianNotes.parse(content, today),
            vault.taskGate(),
        )
        Log.i(TAG, "tasks: ${suggestions.size} suggestions from $today gate=${vault.taskGate()}")
        return suggestions
    }

    private fun showTasks(tasks: List<ObsidianTask>, onTask: (ObsidianTask) -> Unit) {
        hideTasks()
        tasksOverlay = showOverlay { overlay ->
            TydlTheme {
                TasksScreen(
                    tasks = tasks,
                    onTask = { task ->
                        suggestedTasks = emptyList()
                        onTask(task)
                        overlay.remove()
                        tasksOverlay = null
                    },
                    onSomethingElse = {
                        suggestedTasks = emptyList()
                        pendingTask = null
                        overlay.remove()
                        tasksOverlay = null
                        showPromptForm()
                    },
                )
            }
        }
    }

    private fun hideTasks() {
        tasksOverlay?.remove()
        tasksOverlay = null
    }

    /** Checks the picked task off in today's note when the user confirms the session is done. */
    fun completeTask() {
        val taskText = state.selectedTask ?: return
        scope.launch(Dispatchers.IO) {
            val done = vault.completeTask(taskText)
            Log.i(TAG, "completeTask done=$done task=$taskText")
        }
    }

    /** Appends the how-it-went reflection to today's note, if the vault is configured. */
    fun appendReflection(note: String) {
        scope.launch(Dispatchers.IO) {
            val done = vault.appendReflection(note)
            Log.i(TAG, "appendReflection done=$done")
        }
    }

    private fun showExpiry() = whenUnlocked("expiry") {
        val session = state.session ?: return@whenUnlocked
        // An unlock and a deadline can both ask for the expiry; rebuilding it would wipe whatever
        // the user has typed into the extend form.
        if (expiryOverlay != null) {
            Log.i(TAG, "expiry overlay already showing")
            return@whenUnlocked
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "expiry: no overlay permission, using notification")
            notifyAlert(
                title = "Time's up",
                text = "\"${session.reason}\" is past its minutes.",
                extra = EXTRA_SHOW_EXPIRY,
            )
        } else {
            hideExpiry()
            val task = state.selectedTask
            val quote = vault.readTemplate()?.let { ObsidianNotes.templateQuote(it) } ?: "Consistency Before Greatness"
            expiryOverlay = showOverlay { overlay ->
                TydlTheme {
                    ExpiryScreen(
                        session = session,
                        now = now(),
                        extensionsTodayCount = state.extensionsTodayCount,
                        extensionsTodayMinutes = state.extensionsTodayMinutes,
                        quote = quote,
                        task = task,
                        onTaskFinished = { completeTask() },
                        onDone = { note ->
                            endAndLock(this@GuardService)
                            appendReflection(note)
                            overlay.remove()
                            expiryOverlay = null
                        },
                        onStartAnother = { note ->
                            end(this@GuardService)
                            simulateUnlock(this@GuardService)
                            appendReflection(note)
                            overlay.remove()
                            expiryOverlay = null
                        },
                        onExtend = { minutes ->
                            extend(this@GuardService, minutes)
                            overlay.remove()
                            expiryOverlay = null
                        },
                    )
                }
            }
            Log.i(TAG, "expiry overlay shown")
        }
    }

    private fun showDone() = whenUnlocked("done") {
        val session = state.completedSession ?: return@whenUnlocked
        if (!Settings.canDrawOverlays(this)) {
            notifyAlert(
                title = "Session ended",
                text = "Tap to record how it went.",
                extra = EXTRA_SHOW_EXPIRY,
            )
        } else {
            hideDone()
            doneOverlay = showOverlay { overlay ->
                TydlTheme {
                    DoneScreen(
                        session = session,
                        task = state.selectedTask,
                        onTaskFinished = { completeTask() },
                        onDone = { note ->
                            end(this@GuardService)
                            simulateUnlock(this@GuardService)
                            appendReflection(note)
                            overlay.remove()
                            doneOverlay = null
                        },
                        showStartAnother = false,
                    )
                }
            }
            Log.i(TAG, "done overlay shown")
        }
    }

    private fun hideDone() {
        doneOverlay?.remove()
        doneOverlay = null
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

    /**
     * First screen-on after the rollover hour of the current day: creates today's note from the
     * template if needed and carries the previous note's open tasks into today's carry-over slot.
     */
    private fun maybeRollover(now: Long) {
        val todayKey = java.time.LocalDate.now().toString()
        if (lastRolloverDayKey == todayKey) return
        val dayStart = localDayStart(now)
        if (now < dayStart + vault.rolloverHour() * 60_000L) return
        if (performRollover()) lastRolloverDayKey = todayKey
    }

    private fun performRollover(): Boolean {
        if (!vault.isConfigured()) return false
        val todayName = vault.todayFileName()
        if (!vault.hasToday()) {
            Log.i(TAG, "rollover: creating $todayName from template")
            if (!vault.createTodayFromTemplate()) {
                Log.w(TAG, "rollover: failed to create $todayName")
                return false
            }
        }
        val todayContent = vault.readToday() ?: run {
            Log.w(TAG, "rollover: cannot read $todayName")
            return false
        }
        val previousName = ObsidianNotes.latestEarlierDailyNote(
            vault.listDailyNoteNames(),
            todayName,
            vault.filenameFormat(),
        ) ?: run {
            Log.i(TAG, "rollover: no earlier daily note before $todayName")
            return true
        }
        val previousContent = vault.readNote(previousName) ?: run {
            Log.w(TAG, "rollover: cannot read previous $previousName")
            return true
        }
        val plan = ObsidianNotes.planRollover(todayContent, previousContent) ?: run {
            Log.i(TAG, "rollover: nothing to carry from $previousName to $todayName")
            return true
        }
        Log.i(TAG, "rollover: carrying ${plan.carriedTasks.size} tasks from $previousName to $todayName")
        if (!vault.writeToday(plan.updatedToday)) {
            Log.w(TAG, "rollover: failed to write $todayName")
            return false
        }
        val verify = vault.readToday() ?: return false
        if (ObsidianNotes.parse(verify).carryOverTasks.size < plan.carriedTasks.size) {
            Log.w(TAG, "rollover: verify failed for $todayName")
            return false
        }
        if (!vault.writeNote(previousName, plan.updatedPrevious)) {
            Log.w(TAG, "rollover: failed to update previous $previousName")
            return false
        }
        Log.i(TAG, "rollover: complete $previousName -> $todayName")
        return true
    }

    private fun localDayStart(now: Long): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

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
        private const val ACTION_PAUSE = "io.github.serabin1.tydl.action.PAUSE"
        private const val ACTION_UNLOCK = "io.github.serabin1.tydl.action.UNLOCK"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_TASK = "task"
        const val EXTRA_SHOW_PROMPT = "show_prompt"
        const val EXTRA_SHOW_EXPIRY = "show_expiry"
        private const val UNLOCK_DEBOUNCE_MS = 1_500L
        private const val KEYGUARD_RETRY_MS = 600L
        private const val MAX_UNLOCK_ATTEMPTS = 3
        private const val KEYGUARD_POLL_INTERVAL_MS = 500L
        private const val KEYGUARD_POLL_TIMEOUT_MS = 30_000L

        fun start(context: Context) = send(context, Intent(context, GuardService::class.java))

        fun startSession(context: Context, reason: String, minutes: Int, taskText: String? = null) = send(
            context,
            base(context)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_TASK, taskText),
        )

        /** Starts a session for a suggested task; the task text is remembered for completion. */
        fun startTask(context: Context, task: ObsidianTask) = startSession(
            context,
            ObsidianNotes.display(task),
            Session.DEFAULT_MINUTES,
            task.text,
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

        fun extend(context: Context, minutes: Int = Session.EXTENSION_MINUTES) = send(
            context,
            base(context)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_MINUTES, minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES)),
        )

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
