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
import io.github.serabin1.tydl.data.DailyStatsStore
import io.github.serabin1.tydl.data.TydlSettingsStore
import io.github.serabin1.tydl.data.ObsidianVault
import io.github.serabin1.tydl.domain.DoneOutcome
import io.github.serabin1.tydl.domain.PickedTask
import io.github.serabin1.tydl.domain.Gravitas
import io.github.serabin1.tydl.domain.GuardEffect
import io.github.serabin1.tydl.domain.GuardEngine
import io.github.serabin1.tydl.domain.GuardEvent
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.QuickPause
import io.github.serabin1.tydl.domain.Reflections
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.domain.SleepTracker
import io.github.serabin1.tydl.domain.StatsRetention
import io.github.serabin1.tydl.domain.TydlDay
import io.github.serabin1.tydl.domain.describe
import io.github.serabin1.tydl.domain.getTydlDay
import io.github.serabin1.tydl.domain.obsidian.DailyNoteRollover
import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import io.github.serabin1.tydl.domain.obsidian.ObsidianTask
import io.github.serabin1.tydl.domain.obsidian.RolloverResult
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.log.TydlLog
import io.github.serabin1.tydl.ui.DoneScreen
import io.github.serabin1.tydl.ui.ExpiryScreen
import io.github.serabin1.tydl.ui.GravitasScreen
import io.github.serabin1.tydl.ui.MainActivity
import io.github.serabin1.tydl.ui.ReasonTimeScreen
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private lateinit var dailyStatsStore: DailyStatsStore
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private var state = GuardState()

    /** Debounces SCREEN_ON + USER_PRESENT arriving for the same unlock. */
    private var lastUnlockAt = 0L
    private var reasonTimeOverlay: OverlayWindow? = null
    private var expiryOverlay: OverlayWindow? = null
    private var doneOverlay: OverlayWindow? = null
    private var tasksOverlay: OverlayWindow? = null
    private var gravitasOverlay: OverlayWindow? = null
    private var keyguardPollJob: Job? = null

    /** Tasks picked from the vault for the current prompt, if the vault is configured. */
    private var suggestedTasks: List<ObsidianTask> = emptyList()
    /** A task the user tapped; pre-fills reason but keeps friction (time still required). */
    private var pendingTask: ObsidianTask? = null

    /** Checked routine tasks in today's note (note is the record of truth); computed in loadSuggestedTasks. */
    private var todayFinishedCount: Int = 0

    /** Checked routine tasks in yesterday's note, or null when no readable note exists. */
    private var yesterdayFinishedCount: Int? = null

    /** Daily-note rollover; idempotent and re-evaluated against the vault on every trigger. */
    private lateinit var rollover: DailyNoteRollover

    /** One in-flight rollover at a time; the triggers are frequent and overlap by design. */
    private var rolloverJob: Job? = null

    /** Serializes note read-modify-writes so reflections and reason-as-task both survive. */
    private val noteWriteMutex = Mutex()

    private lateinit var vault: ObsidianVault

    /**
     * A bounded buffer of recent screen on/off events, used to compute overnight sleep. Capped so
     * a long-running process never grows it without bound. Only kept when sleep tracking is on.
     */
    private val screenEvents = ArrayDeque<SleepTracker.ScreenEvent>()
    private val sleepLock = Any()

    /** Interrupts the deadline loop when something other than the clock changes what to wait for. */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    TydlLog.i(LogTag.UNLOCK, "USER_PRESENT (keyguardLocked=${keyguard.isKeyguardLocked})")
                    recordScreenEvent(SleepTracker.ScreenState.UNLOCKED_ON)
                    cancelKeyguardPoll("user present")
                    maybeRollover("user present")
                    onUnlock()
                }

                Intent.ACTION_SCREEN_ON -> {
                    val locked = keyguard.isKeyguardLocked
                    TydlLog.i(LogTag.UNLOCK, "SCREEN_ON (keyguardLocked=$locked)")
                    recordScreenEvent(
                        if (locked) SleepTracker.ScreenState.LOCKED_ON
                        else SleepTracker.ScreenState.UNLOCKED_ON,
                    )
                    maybeRollover("screen on")
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
                    TydlLog.i(LogTag.UNLOCK, "SCREEN_OFF (keyguardLocked=${keyguard.isKeyguardLocked})")
                    recordScreenEvent(SleepTracker.ScreenState.OFF)
                    cancelKeyguardPoll("screen off")
                    handle(GuardEvent.ScreenOff(now()))
                    wakeSignal.trySend(Unit)
                }

                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    TydlLog.i(LogTag.SERVICE, "${intent.action}: clock changed")
                    maybeRollover("clock changed")
                    wakeSignal.trySend(Unit)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        store = GuardStore(this)
        dailyStatsStore = DailyStatsStore(this) { settingsStore.load().rolloverHour }
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        vault = ObsidianVault(this)
        rollover = DailyNoteRollover(vault)
        state = store.load()
        TydlLog.i(LogTag.SERVICE, "service created; state=${stateLabel()}")
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
        TydlLog.i(LogTag.SERVICE, "foreground started")
        maybeRollover("service created")
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
                    TydlLog.i(LogTag.SERVICE, "no deadline pending; waiting for a state change")
                    wakeSignal.receive()
                    continue
                }
                val wait = (at - now()).coerceAtLeast(0L)
                TydlLog.i(LogTag.SERVICE, "next deadline in $wait ms")
                val signalled = withTimeoutOrNull(wait) { wakeSignal.receive() }
                if (signalled == null) {
                    TydlLog.i(LogTag.SERVICE, "deadline reached")
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
        // A plain start (app open, boot, sticky restart) is also a rollover trigger.
        if (intent?.action == null) maybeRollover("service start")
        val command = intent ?: return START_STICKY
        when (command.action) {
            ACTION_START -> {
                val minutes = command.getIntExtra(EXTRA_MINUTES, Session.DEFAULT_MINUTES)
                val enteredReason = command.getStringExtra(EXTRA_REASON).orEmpty()
                val taskText = command.getStringExtra(EXTRA_TASK)
                val taskDisplay = command.getStringExtra(EXTRA_TASK_DISPLAY)
                // startSession only sets this flag for a typed reason, never for a picked task,
                // so the reason cannot be written back as a duplicate. Unlike before it does not
                // depend on taskText: a reason saved as a task is also the task this session
                // remembers so it can be checked off when done.
                val addAsTask = command.getBooleanExtra(EXTRA_ADD_AS_TASK, false)
                if (settingsStore.load().dailyStatsEnabled) {
                    dailyStatsStore.recordSessionStart(minutes, now())
                }
                handle(
                    GuardEvent.Start(
                        reason = enteredReason.ifBlank { "Unspecified" },
                        minutes = minutes,
                        now = now(),
                        task = taskText?.let { PickedTask(raw = it, display = taskDisplay ?: it) },
                    ),
                )
                // The session reason is written up front: matching it later to the nth checked
                // task breaks when sessions overlap or tasks are checked off in another order.
                state.session?.reason?.let { appendReflection(Reflections.session(it)) }
                // A reason marked in R&T becomes a backlog task in today's note. The note write
                // is off the main thread and never blocks or cancels the session. It shares the
                // reflection write's lock so the two read-modify-writes cannot clobber each other.
                if (addAsTask && enteredReason.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        ensureRollover("reason as task")
                        val saved = noteWriteMutex.withLock { vault.addTask(enteredReason) }
                        TydlLog.i(LogTag.TASKS, "reason saved as task=$saved")
                    }
                }
            }

            ACTION_END -> handle(GuardEvent.End)
            ACTION_EXTEND -> extendSession(
                reason = command.getStringExtra(EXTRA_REASON).orEmpty(),
                minutes = command.getIntExtra(EXTRA_MINUTES, Session.EXTENSION_MINUTES),
            )
            ACTION_PAUSE -> handle(
                GuardEvent.Paused(
                    until = command.getLongExtra(EXTRA_UNTIL, 0L),
                    promptOnPauseEnd = command.getBooleanExtra(EXTRA_PROMPT_ON_END, false),
                ),
            )
            ACTION_RESUME -> handle(GuardEvent.Resume)
            ACTION_UNLOCK -> {
                TydlLog.i(LogTag.UNLOCK, "manual unlock requested")
                onUnlock()
            }
            ACTION_COMPLETE_TASK -> completeTask()
            ACTION_APPEND_REFLECTION -> appendReflection(command.getStringExtra(EXTRA_REFLECTION).orEmpty())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        TydlLog.i(LogTag.SERVICE, "service destroyed")
        cancelKeyguardPoll("destroy")
        scope.cancel()
        hideReasonTime()
        hideExpiry()
        hideDone()
        hideTasks()
        hideGravitas()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: IllegalArgumentException) {
            TydlLog.w(LogTag.ERROR, "receiver already gone", e)
        }
        super.onDestroy()
    }

    private fun onUnlock() {
        // The keyguard coming down changes whether a deadline can be shown, even when the
        // unlock itself changes no state (a live session, for instance).
        wakeSignal.trySend(Unit)
        val at = now()
        if (at - lastUnlockAt < UNLOCK_DEBOUNCE_MS) {
            TydlLog.i(LogTag.UNLOCK, "unlock debounced (${at - lastUnlockAt} ms)")
            return
        }
        lastUnlockAt = at
        store.saveLastUnlock(at)
        if (settingsStore.load().dailyStatsEnabled) dailyStatsStore.recordUnlock(at)
        handle(GuardEvent.Unlocked(at))
    }

    private val settingsStore by lazy { TydlSettingsStore(this) }

    /** Feeds a screen/keyguard event into the sleep buffer when it could complete a gap. */
    private fun recordScreenEvent(state: SleepTracker.ScreenState) {
        val settings = settingsStore.load()
        if (!settings.dailyStatsEnabled || !settings.sleepTrackingEnabled) return
        val at = now()
        val sleep = synchronized(sleepLock) {
            screenEvents.addLast(SleepTracker.ScreenEvent(at, state))
            while (screenEvents.size > SCREEN_EVENT_CAP) screenEvents.removeFirst()
            SleepTracker.computeSleep(screenEvents.toList())
        }
        if (sleep != null) {
            dailyStatsStore.recordSleep(sleep.sleepStart, sleep.wakeTime)
            TydlLog.i(
                LogTag.SLEEP,
                "sleep detected: ${SleepTracker.formatDuration(sleep.wakeTime - sleep.sleepStart)}",
            )
        }
    }

    /** Polls the keyguard after a locked SCREEN_ON until it unlocks or times out. */
    private fun startKeyguardPoll() {
        cancelKeyguardPoll("restart")
        keyguardPollJob = scope.launch {
            TydlLog.i(LogTag.UNLOCK, "keyguard poll started")
            val deadline = now() + KEYGUARD_POLL_TIMEOUT_MS
            while (isActive && now() < deadline) {
                delay(KEYGUARD_POLL_INTERVAL_MS)
                if (!keyguard.isKeyguardLocked) {
                    TydlLog.i(LogTag.UNLOCK, "keyguard poll success")
                    // The keyguard came down while the screen was already lit; no SCREEN_ON or
                    // USER_PRESENT may follow, so this is the wake event for sleep tracking.
                    recordScreenEvent(SleepTracker.ScreenState.UNLOCKED_ON)
                    onUnlock()
                    return@launch
                }
                if (!power.isInteractive) {
                    TydlLog.i(LogTag.UNLOCK, "keyguard poll stopped: screen off")
                    return@launch
                }
            }
            TydlLog.i(LogTag.UNLOCK, "keyguard poll timeout")
        }
    }

    private fun cancelKeyguardPoll(reason: String) {
        keyguardPollJob?.let {
            if (it.isActive) {
                it.cancel()
                TydlLog.i(LogTag.UNLOCK, "keyguard poll cancelled: $reason")
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
        TydlLog.i(
            LogTag.SESSION,
            "event=${event.describe()} effect=${step.effect.describe()} state=${stateLabel()}",
        )
        when (step.effect) {
            GuardEffect.ShowReasonTime -> showReasonTime()
            GuardEffect.ShowExpiry -> showExpiry()
            GuardEffect.ShowDone -> showDone()
            GuardEffect.None -> Unit
        }
        val now = now()
        if (state.session != null) hideReasonTime()
        if (state.session?.isOverrun(now) != true) hideExpiry()
        if (state.completedSession == null) hideDone()
        if (changed) wakeSignal.trySend(Unit)
    }

    private fun showReasonTime() {
        whenUnlocked("prompt") {
            scope.launch {
                suggestedTasks = withContext(Dispatchers.IO) {
                    ensureRollover("prompt")
                    loadSuggestedTasks()
                }
                if (suggestedTasks.isNotEmpty() && pendingTask == null) {
                    showTasks(suggestedTasks) { task ->
                        suggestedTasks = emptyList()
                        pendingTask = task
                        showReasonTime()
                    }
                    return@launch
                }
                showReasonTimeForm()
            }
        }
    }

    private fun showReasonTimeForm() {
        if (!Settings.canDrawOverlays(this)) {
            TydlLog.i(LogTag.REASON_TIME, "no overlay permission, using notification")
            notifyAlert(
                title = "State your reason",
                text = "Tap to tell Tydl why you unlocked.",
                extra = EXTRA_SHOW_REASON_TIME,
            )
        } else {
            hideReasonTime()
            val initialReason = pendingTask?.let { ObsidianNotes.display(it) } ?: ""
            val taskText = pendingTask?.text
            val taskDisplay = pendingTask?.let { ObsidianNotes.display(it) }
            reasonTimeOverlay = showOverlay { overlay ->
                TydlTheme {
                    ReasonTimeScreen(initialReason = initialReason, onStart = { reason, minutes, addAsTask ->
                        reasonTimeOverlay = null
                        val settings = settingsStore.load()
                        if (settings.gravitasEnabled && Gravitas.shouldShow(minutes)) {
                            showGravitas(reason, minutes, taskText, taskDisplay, addAsTask)
                        } else {
                            startSession(this@GuardService, reason, minutes, taskText, taskDisplay, addAsTask)
                            pendingTask = null
                        }
                        overlay.remove()
                    })
                }
            }
            TydlLog.i(LogTag.REASON_TIME, "overlay shown")
        }
    }

    /**
     * Shows the Gravitas overlay, then starts the session when its countdown elapses. Not
     * skippable: the overlay has no skip control and owns the flow until it finishes.
     */
    private fun showGravitas(
        reason: String,
        minutes: Int,
        taskText: String?,
        taskDisplay: String?,
        addAsTask: Boolean,
    ) {
        if (!Settings.canDrawOverlays(this)) {
            // No overlay: skip Gravitas rather than block the session on a notification.
            startSession(this, reason, minutes, taskText, taskDisplay, addAsTask)
            pendingTask = null
            return
        }
        scope.launch {
            val (quote, yesterdayReflections, todayFinished, sleep) =
                withContext(Dispatchers.IO) { loadGravitasContent() }
            hideGravitas()
            gravitasOverlay = showOverlay { overlay ->
                TydlTheme {
                    GravitasScreen(
                        requestedMinutes = minutes,
                        quote = quote,
                        yesterdayReflections = yesterdayReflections,
                        todayFinishedTasks = todayFinished,
                        lastNightSleep = sleep,
                        onFinished = {
                            startSession(this@GuardService, reason, minutes, taskText, taskDisplay, addAsTask)
                            pendingTask = null
                            overlay.remove()
                            gravitasOverlay = null
                        },
                    )
                }
            }
            TydlLog.i(LogTag.GRAVITAS, "gravitas overlay shown ($minutes min)")
        }
    }

    /** The four Gravitas content pieces, read off the main thread. */
    private fun loadGravitasContent(): GravitasContent {
        val quote = vault.readTemplate()?.let { ObsidianNotes.templateQuote(it) } ?: ""
        val yesterdayReflections = runCatching {
            val format = vault.filenameFormat()
            val rolloverHour = settingsStore.load().rolloverHour
            val prevDate = TydlDay.of(now(), rolloverHour).minusDays(1)
            val prevName = ObsidianNotes.dailyNoteName(format, prevDate)
            vault.readNote(prevName)?.let { ObsidianNotes.parse(it).reflection }
        }.getOrNull()
        val todayFinished = runCatching {
            vault.readToday()?.let { ObsidianNotes.parse(it) }
                ?.routineTasks?.filter { it.checked }?.map { ObsidianNotes.display(it) }
                ?: emptyList()
        }.getOrNull() ?: emptyList()
        val settings = settingsStore.load()
        val sleep = if (settings.dailyStatsEnabled && settings.sleepTrackingEnabled) {
            val todayStats = dailyStatsStore.loadToday()
            val sleepStats = if (todayStats.sleepStart != null) todayStats else {
                val rolloverHour = settings.rolloverHour
                val prevDate = TydlDay.of(now(), rolloverHour).minusDays(1)
                dailyStatsStore.load(prevDate.toString())
            }
            sleepStats.sleepStart?.let { start ->
                val wake = sleepStats.wakeTime ?: start
                SleepTracker.formatDuration(wake - start)
            }
        } else null
        return GravitasContent(quote, yesterdayReflections, todayFinished, sleep)
    }

    private data class GravitasContent(
        val quote: String,
        val yesterdayReflections: String?,
        val todayFinished: List<String>,
        val sleep: String?,
    )

    private fun loadSuggestedTasks(): List<ObsidianTask> {
        todayFinishedCount = 0
        yesterdayFinishedCount = null
        if (!vault.isConfigured()) {
            TydlLog.i(LogTag.TASKS, "vault not configured")
            return emptyList()
        }
        val today = vault.todayFileName()
        if (!vault.hasToday()) {
            TydlLog.i(LogTag.TASKS, "no note for $today")
            return emptyList()
        }
        val content = vault.readToday() ?: run {
            TydlLog.w(LogTag.TASKS, "failed to read $today")
            return emptyList()
        }
        val note = ObsidianNotes.parse(content, today)
        val backlog = note.carryOverTasks.filter { !it.checked }
        val randomIndex = if (backlog.isNotEmpty()) java.util.Random().nextInt(backlog.size) else null
        val suggestions = ObsidianNotes.suggestionsWithRandom(
            note,
            vault.taskGate(),
            randomBacklogIndex = randomIndex,
        )
        todayFinishedCount = note.routineTasks.count { it.checked }
        val statsEnabled = settingsStore.load().dailyStatsEnabled
        yesterdayFinishedCount = if (statsEnabled) computeYesterdayFinishedCount() else null

        TydlLog.i(
            LogTag.TASKS,
            "${suggestions.size} suggestions from $today gate=${vault.taskGate()}",
        )
        return suggestions
    }

    /** Checked routine tasks in yesterday's (previous Tydl day) note, or null when no readable note exists. */
    private fun computeYesterdayFinishedCount(): Int? = runCatching {
        val format = vault.filenameFormat()
        val rolloverHour = settingsStore.load().rolloverHour
        val prevDate = TydlDay.of(now(), rolloverHour).minusDays(1)
        val prevName = ObsidianNotes.dailyNoteName(format, prevDate)
        val content = vault.readNote(prevName) ?: return@runCatching null
        ObsidianNotes.parse(content).routineTasks.count { it.checked }
    }.getOrNull()

    private fun showTasks(tasks: List<ObsidianTask>, onTask: (ObsidianTask) -> Unit) {
        hideTasks()
        val statsEnabled = settingsStore.load().dailyStatsEnabled
        val todayDone = if (statsEnabled) todayFinishedCount else null
        val yesterdayDone = if (statsEnabled) yesterdayFinishedCount else null
        tasksOverlay = showOverlay { overlay ->
            TydlTheme {
                TasksScreen(
                    tasks = tasks,
                    todayTasksDone = todayDone,
                    yesterdayTasksDone = yesterdayDone,
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
                        showReasonTimeForm()
                    },
                    onJustTwoMinutes = {
                        suggestedTasks = emptyList()
                        pendingTask = null
                        overlay.remove()
                        tasksOverlay = null
                        hideReasonTime()
                        quickPause(this@GuardService)
                        TydlLog.i(LogTag.SESSION, "just 2 mins requested")
                    },
                )
            }
        }
    }


    private fun hideTasks() {
        tasksOverlay?.remove()
        tasksOverlay = null
    }

    private fun hideGravitas() {
        gravitasOverlay?.remove()
        gravitasOverlay = null
    }

    /** Checks the picked task off in today's note when the user confirms the session is done. */
    fun completeTask() {
        val taskText = state.selectedTask?.raw ?: return
        scope.launch(Dispatchers.IO) {
            ensureRollover("task check-off")
            val done = noteWriteMutex.withLock { vault.completeTask(taskText) }
            if (done && settingsStore.load().dailyStatsEnabled) dailyStatsStore.recordTaskDone()
            TydlLog.i(LogTag.TASKS, "task check-off done=$done")
        }
    }

    /** Appends the how-it-went reflection to today's note, if the vault is configured. */
    fun appendReflection(reflection: String) {
        scope.launch(Dispatchers.IO) {
            ensureRollover("reflection")
            val done = noteWriteMutex.withLock { vault.appendReflection(reflection) }
            TydlLog.i(LogTag.REFLECTION, "reflection saved=$done")
        }
    }

    /**
     * Extends a live session and, when the engine accepted it, appends the extension's reason to
     * today's REFLECTION slot, naming the starting reason so the bullet stands on its own.
     */
    private fun extendSession(reason: String, minutes: Int) {
        val safeMinutes = minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES)
        val before = state.session
        handle(GuardEvent.Extend(now(), safeMinutes, reason))
        val applied = state.session != null &&
            before != null &&
            state.session!!.extensionsUsed == before.extensionsUsed + 1
        if (applied) {
            appendReflection(Reflections.extension(safeMinutes, reason, before.reason))
            if (settingsStore.load().dailyStatsEnabled) dailyStatsStore.recordExtension(safeMinutes)
        }
    }

    private fun showExpiry() = whenUnlocked("expiry") {
        val session = state.session ?: return@whenUnlocked
        val todayStats = if (settingsStore.load().dailyStatsEnabled) dailyStatsStore.loadToday() else null
        // An unlock and a deadline can both ask for the expiry; rebuilding it would wipe whatever
        // the user has typed into the extend form.
        if (expiryOverlay != null) {
            TydlLog.i(LogTag.SESSION, "expiry overlay already showing")
            return@whenUnlocked
        }
        if (!Settings.canDrawOverlays(this)) {
            TydlLog.i(LogTag.SESSION, "expiry: no overlay permission, using notification")
            notifyAlert(
                title = "Time's up",
                text = "\"${session.reason}\" is past its minutes.",
                extra = EXTRA_SHOW_EXPIRY,
            )
        } else {
            hideExpiry()
            val task = state.selectedTask?.raw
            val quote = vault.readTemplate()?.let { ObsidianNotes.templateQuote(it) } ?: "Consistency Before Greatness"
            expiryOverlay = showOverlay { overlay ->
                TydlTheme {
                    ExpiryScreen(
                        session = session,
                        now = now(),
                        extensionsTodayCount = todayStats?.extensionsCount ?: 0,
                        extensionsTodayMinutes = todayStats?.extensionsMinutes ?: 0,
                        quote = quote,
                        task = task,
                        onTaskFinished = { completeTask() },
                        onDone = { reflection ->
                            endAndLock(this@GuardService)
                            appendReflection(reflection)
                            overlay.remove()
                            expiryOverlay = null
                        },
                        onStartAnother = { reflection ->
                            end(this@GuardService)
                            simulateUnlock(this@GuardService)
                            appendReflection(reflection)
                            overlay.remove()
                            expiryOverlay = null
                        },
                        onExtend = { reason, minutes ->
                            extend(this@GuardService, reason, minutes)
                            overlay.remove()
                            expiryOverlay = null
                        },
                    )
                }
            }
            TydlLog.i(LogTag.SESSION, "expiry overlay shown")
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
                        task = state.selectedTask?.raw,
                        onTaskFinished = { completeTask() },
                        onDone = { reflection ->
                            end(this@GuardService)
                            simulateUnlock(this@GuardService)
                            appendReflection(reflection)
                            overlay.remove()
                            doneOverlay = null
                        },
                        showStartAnother = false,
                    )
                }
            }
            TydlLog.i(LogTag.SESSION, "done overlay shown")
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
        TydlLog.i(LogTag.UNLOCK, "$label: keyguard still locked (attempt $attempt)")
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

    private fun hideReasonTime() {
        reasonTimeOverlay?.remove()
        reasonTimeOverlay = null
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

    /** Never includes the reason or task text: the log is for decisions, not user prose. */
    private fun stateLabel(): String = when {
        state.isPaused(now()) -> "paused"
        state.session != null -> "active"
        else -> "none"
    }

    /**
     * Triggers the idempotent daily-note rollover off the main thread. Every trigger (screen on,
     * unlock, app start, clock change, prompt) calls this: it re-derives what to do from the
     * vault's actual state and never memoizes "already ran today".
     */
    private fun maybeRollover(reason: String) {
        pruneDailyStats()
        if (!vault.isConfigured()) return
        if (rolloverJob?.isActive == true) return
        rolloverJob = scope.launch(Dispatchers.IO) { ensureRollover(reason) }
    }

    /** Applies the auto-delete policy; a no-op once the stored dates are inside the window. */
    private fun pruneDailyStats() {
        val settings = settingsStore.load()
        if (!settings.dailyStatsEnabled) return
        val toDelete = StatsRetention.datesToDelete(
            dates = dailyStatsStore.dates(),
            today = getTydlDay(at = now(), rolloverHour = settings.rolloverHour),
            everyNDays = settings.dailyStatsAutoDeleteEveryNDays,
            deleteSaturday = settings.dailyStatsAutoDeleteSaturday,
            deleteSunday = settings.dailyStatsAutoDeleteSunday,
        )
        if (toDelete.isEmpty()) return
        dailyStatsStore.clearDates(toDelete)
        TydlLog.i(LogTag.SERVICE, "daily stats auto-delete: ${toDelete.size} day(s)")
    }

    private suspend fun ensureRollover(reason: String): RolloverResult {
        val started = System.currentTimeMillis()
        val result = try {
            rollover.ensureToday()
        } catch (e: Exception) {
            TydlLog.w(LogTag.ROLLOVER, "rollover ($reason) threw", e)
            RolloverResult.Failed(e.message ?: "exception")
        }
        TydlLog.i(
            LogTag.ROLLOVER,
            "rollover ($reason): ${result.describe()} " +
                "(today=${vault.todayFileName()}, ${System.currentTimeMillis() - started}ms)",
        )
        return result
    }

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
                TydlLog.w(LogTag.ERROR, "could not remove overlay", e)
            } finally {
                store.clear()
            }
        }
    }

    companion object {
        @Volatile
        private var running = false

        /** Same process as the activity, so this is cheaper and more reliable than dumpsys. */
        fun isRunning(): Boolean = running

        private const val ACTION_START = "io.github.serabin1.tydl.action.START"
        private const val ACTION_END = "io.github.serabin1.tydl.action.END"
        private const val ACTION_EXTEND = "io.github.serabin1.tydl.action.EXTEND"
        private const val ACTION_PAUSE = "io.github.serabin1.tydl.action.PAUSE"
        private const val ACTION_RESUME = "io.github.serabin1.tydl.action.RESUME"
        private const val ACTION_UNLOCK = "io.github.serabin1.tydl.action.UNLOCK"
        private const val ACTION_COMPLETE_TASK = "io.github.serabin1.tydl.action.COMPLETE_TASK"
        private const val ACTION_APPEND_REFLECTION = "io.github.serabin1.tydl.action.APPEND_REFLECTION"
        private const val EXTRA_REFLECTION = "reflection"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_PROMPT_ON_END = "prompt_on_end"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_TASK_DISPLAY = "task_display"
        private const val EXTRA_ADD_AS_TASK = "add_as_task"
        const val EXTRA_SHOW_REASON_TIME = "show_prompt"
        const val EXTRA_SHOW_EXPIRY = "show_expiry"
        private const val UNLOCK_DEBOUNCE_MS = 1_500L
        private const val KEYGUARD_RETRY_MS = 600L
        private const val MAX_UNLOCK_ATTEMPTS = 3
        private const val KEYGUARD_POLL_INTERVAL_MS = 500L
        private const val KEYGUARD_POLL_TIMEOUT_MS = 30_000L
        /** Keep ~2 days of screen events so overnight sleep can be recomputed across midnight. */
        private const val SCREEN_EVENT_CAP = 256

        fun start(context: Context) = send(context, Intent(context, GuardService::class.java))

        fun startSession(
            context: Context,
            reason: String,
            minutes: Int,
            taskText: String? = null,
            taskDisplay: String? = null,
            addAsTask: Boolean = false,
        ) {
            // A picked task is already in the backlog; never write its reason back as a second task.
            val reasonAsTask = addAsTask && taskText == null
            val resolvedTask = ObsidianNotes.sessionTask(taskText, reason, reasonAsTask)
            send(
                context,
                base(context)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_MINUTES, minutes)
                    .putExtra(EXTRA_TASK, resolvedTask)
                    .putExtra(EXTRA_TASK_DISPLAY, taskDisplay ?: resolvedTask)
                    .putExtra(EXTRA_ADD_AS_TASK, reasonAsTask),
            )
        }

        /** Starts a session for a suggested task; the task text is remembered for completion. */
        fun startTask(context: Context, task: ObsidianTask) = startSession(
            context,
            ObsidianNotes.display(task),
            Session.DEFAULT_MINUTES,
            task.text,
            ObsidianNotes.display(task),
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

        fun extend(
            context: Context,
            reason: String,
            minutes: Int = Session.EXTENSION_MINUTES,
        ) = send(
            context,
            base(context)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_MINUTES, minutes.coerceIn(Session.MIN_MINUTES, Session.MAX_MINUTES))
                .putExtra(EXTRA_REASON, reason),
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
            base(context).setAction(ACTION_RESUME),
        )

        /** The R&T escape hatch: two quiet minutes, then the prompt comes back on its own. */
        fun quickPause(context: Context) = send(
            context,
            base(context)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_UNTIL, QuickPause.until(System.currentTimeMillis()))
                .putExtra(EXTRA_PROMPT_ON_END, true),
        )

        /** Diagnostics: run the unlock path without locking the phone. */
        fun simulateUnlock(context: Context) = send(
            context,
            base(context).setAction(ACTION_UNLOCK),
        )

        /** Checks the picked task off in today's note via the running service (mutex + rollover). */
        fun completeTask(context: Context) = send(context, base(context).setAction(ACTION_COMPLETE_TASK))

        /** Appends the how-it-went reflection via the running service (mutex + rollover). */
        fun appendReflection(context: Context, reflection: String) = send(
            context,
            base(context).setAction(ACTION_APPEND_REFLECTION).putExtra(EXTRA_REFLECTION, reflection),
        )

        private fun base(context: Context) = Intent(context, GuardService::class.java)

        private fun send(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                TydlLog.w(LogTag.ERROR, "could not start the guard", e)
            }
        }
    }
}
