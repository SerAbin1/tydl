package io.github.serabin1.tydl.ui

import android.Manifest
import android.app.StatusBarManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.serabin1.tydl.R
import io.github.serabin1.tydl.data.ObsidianSettings
import io.github.serabin1.tydl.data.ObsidianSettingsStore
import io.github.serabin1.tydl.data.ObsidianVault
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.serabin1.tydl.data.GuardStore
import io.github.serabin1.tydl.data.DailyStatsStore
import io.github.serabin1.tydl.domain.DailyStats
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.SleepTracker
import io.github.serabin1.tydl.domain.log.LogEntry
import io.github.serabin1.tydl.domain.log.LogFormat
import io.github.serabin1.tydl.guard.GuardService
import io.github.serabin1.tydl.guard.LockAdminReceiver
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.log.TydlLog
import io.github.serabin1.tydl.tile.DailyNoteTileService
import io.github.serabin1.tydl.ui.theme.Edge
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide
import io.github.serabin1.tydl.ui.theme.TydlTheme
import java.util.Date

/**
 * Not a settings screen in v0.1: status, the pause escape hatch, permissions the guard cannot
 * work without, and the diagnostics needed to make unlock detection trustworthy on OEM ROMs.
 */
class MainActivity : ComponentActivity() {

    private var guardState by mutableStateOf(GuardState())
    private var lastUnlockAt by mutableStateOf(0L)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var deviceAdminGranted by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(false)
    private var fallback by mutableStateOf<String?>(null)
    private var earlyEnd by mutableStateOf(false)
    private lateinit var obsidianVault: ObsidianVault
    private lateinit var settingsStore: ObsidianSettingsStore
    private lateinit var dailyStatsStore: DailyStatsStore
    private var obsidianSettings by mutableStateOf(ObsidianSettings.defaults())
    private var showSettings by mutableStateOf(false)
    private var showLogs by mutableStateOf(false)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val vaultPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            obsidianSettings = obsidianSettings.copy(vaultUri = treeUri.toString())
            settingsStore.save(obsidianSettings)
            TydlLog.i(LogTag.SETTINGS, "vault folder selected")
            refresh()
        }
    }

    private val templatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            obsidianSettings = obsidianSettings.copy(templateUri = uri.toString())
            settingsStore.save(obsidianSettings)
            TydlLog.i(LogTag.SETTINGS, "template file selected")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TydlLog.init(applicationContext)
        TydlLog.i(LogTag.APP, "app opened")
        GuardService.start(this)
        obsidianVault = ObsidianVault(this)
        settingsStore = ObsidianSettingsStore(this)
        obsidianSettings = settingsStore.load()
        dailyStatsStore = DailyStatsStore(this) { obsidianSettings.rolloverHour }
        fallback = fallbackFrom(intent)
        setContent {
            TydlTheme {
                when {
                    showLogs -> {
                        val logEntries by TydlLog.entries.collectAsState()
                        LogsScreen(
                            entries = logEntries,
                            vaultConfigured = obsidianSettings.vaultUri != null,
                            todayNote = obsidianVault.todayFileName(),
                            rolloverHour = obsidianSettings.rolloverHour,
                            serviceRunning = isGuardRunning(),
                            onBack = { showLogs = false },
                            onClear = { TydlLog.clear() },
                            onShare = { shareLogs(logEntries) },
                        )
                    }

                    showSettings -> SettingsScreen(
                        settings = obsidianSettings,
                        dailyStats = dailyStatsStore.loadToday(),
                        dailyStatsStore = dailyStatsStore,
                        onBack = { showSettings = false },
                        onPickVault = { vaultPickerLauncher.launch(null) },
                        onPickTemplate = { templatePickerLauncher.launch(arrayOf("text/*", "text/markdown", "application/octet-stream")) },
                        onAddTile = ::requestAddDailyNoteTile,
                        onViewLogs = { showLogs = true },
                        onSettingsChange = { s ->
                            logSettingsChange(obsidianSettings, s)
                            obsidianSettings = s
                            settingsStore.save(s)
                        },
                    )

                    earlyEnd -> {
                        val session = guardState.session
                        if (session != null) {
                            EarlyEndScreen(
                                session = session,
                                task = guardState.selectedTask,
                                onStartAnother = { note ->
                                    GuardService.appendReflection(this, note)
                                    GuardService.end(this)
                                    GuardService.simulateUnlock(this)
                                    earlyEnd = false
                                    refresh()
                                },
                                onTaskFinished = {
                                    val task = guardState.selectedTask ?: return@EarlyEndScreen
                                    GuardService.completeTask(this)
                                },
                                onDone = { note ->
                                    GuardService.appendReflection(this, note)
                                    GuardService.endAndLock(this)
                                    earlyEnd = false
                                    refresh()
                                },
                            )
                        } else {
                            earlyEnd = false
                            MainContent()
                        }
                    }

                    fallback == GuardService.EXTRA_SHOW_PROMPT -> PromptScreen(
                        onStart = { reason, minutes, addAsTask ->
                            GuardService.startSession(
                                this,
                                reason,
                                minutes,
                                addAsTask = addAsTask,
                            )
                            fallback = null
                            refresh()
                        },
                    )

                    fallback == GuardService.EXTRA_SHOW_EXPIRY -> {
                        val completed = guardState.completedSession
                        val session = guardState.session
                        when {
                            completed != null -> DoneScreen(
                                session = completed,
                                task = guardState.selectedTask,
                                onTaskFinished = {
                                    val task = guardState.selectedTask ?: return@DoneScreen
                                    GuardService.completeTask(this)
                                },
                                onDone = { note ->
                                    GuardService.appendReflection(this, note)
                                    GuardService.end(this)
                                    GuardService.simulateUnlock(this)
                                    fallback = null
                                    refresh()
                                },
                            )

                            session != null -> ExpiryScreen(
                                session = session,
                                now = System.currentTimeMillis(),
                                extensionsTodayCount = dailyStatsStore.loadToday().extensionsCount,
                                extensionsTodayMinutes = dailyStatsStore.loadToday().extensionsMinutes,
                                task = guardState.selectedTask,
                                onTaskFinished = {
                                    val task = guardState.selectedTask ?: return@ExpiryScreen
                                    GuardService.completeTask(this)
                                },
                                onDone = { note ->
                                    GuardService.appendReflection(this, note)
                                    GuardService.endAndLock(this)
                                    fallback = null
                                    refresh()
                                },
                                onStartAnother = { note ->
                                    GuardService.appendReflection(this, note)
                                    GuardService.end(this)
                                    GuardService.simulateUnlock(this)
                                    fallback = null
                                    refresh()
                                },
                                onExtend = { reason, minutes ->
                                    GuardService.extend(this, reason, minutes)
                                    fallback = null
                                    refresh()
                                },
                            )

                            else -> {
                                fallback = null
                                MainContent()
                            }
                        }
                    }

                    else -> MainContent()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fallbackFrom(intent)?.let { fallback = it }
    }

    private fun fallbackFrom(intent: Intent?): String? = when {
        intent?.getBooleanExtra(GuardService.EXTRA_SHOW_PROMPT, false) == true ->
            GuardService.EXTRA_SHOW_PROMPT
        intent?.getBooleanExtra(GuardService.EXTRA_SHOW_EXPIRY, false) == true ->
            GuardService.EXTRA_SHOW_EXPIRY
        else -> null
    }

    /** Android 13+ system prompt; the user must approve, and the call needs a foreground app. */
    private fun requestAddDailyNoteTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(StatusBarManager::class.java) ?: return
        manager.requestAddTileService(
            ComponentName(this, DailyNoteTileService::class.java),
            getString(R.string.tile_daily_note_label),
            Icon.createWithResource(this, R.drawable.ic_tile_daily_note),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Daily note tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Daily note tile is already added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Daily note tile not added"
                else -> "Could not add the daily note tile"
            }
            TydlLog.i(LogTag.SETTINGS, "tile add: $message")
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun MainContent() {
        MainScreen(
            state = guardState,
            lastUnlockAt = lastUnlockAt,
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            deviceAdminGranted = deviceAdminGranted,
            batteryExempt = batteryExempt,
            onPause = { minutes ->
                GuardService.pause(this, minutes)
                refresh()
            },
            onResume = {
                GuardService.resume(this)
                refresh()
            },
            onAllowOverlay = ::openOverlaySettings,
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onAllowDeviceAdmin = ::openDeviceAdminSettings,
            onAllowBattery = ::requestBatteryExemption,
            onTestUnlock = { GuardService.simulateUnlock(this) },
            onEndEarly = { earlyEnd = true },
            onOpenSettings = { showSettings = true },
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val store = GuardStore(this)
        guardState = store.load()
        lastUnlockAt = store.loadLastUnlock()
        obsidianSettings = settingsStore.load()
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        deviceAdminGranted = LockAdminReceiver.isActive(this)
        batteryExempt = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }

    /** Discrete settings only: the filename field fires per keystroke, so it stays out of the log. */
    private fun logSettingsChange(old: ObsidianSettings, new: ObsidianSettings) {
        if (old.rolloverHour != new.rolloverHour) {
            TydlLog.i(LogTag.SETTINGS, "rollover hour = ${new.rolloverHour}")
        }
        if (old.taskGate != new.taskGate) {
            TydlLog.i(LogTag.SETTINGS, "task gate = ${new.taskGate}")
        }
    }

    private fun shareLogs(entries: List<LogEntry>) {
        val text = entries.asReversed().joinToString("\n") { LogFormat.line(it) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Tydl logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Tydl logs"))
    }

    private fun isGuardRunning(): Boolean = GuardService.isRunning()

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openDeviceAdminSettings() {
        try {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, LockAdminReceiver.componentName(this@MainActivity))
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Tydl needs device admin so \"I'm done\" can lock your screen.",
                    )
                },
            )
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }
}

@Composable
private fun MainScreen(
    state: GuardState,
    lastUnlockAt: Long,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    deviceAdminGranted: Boolean,
    batteryExempt: Boolean,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
    onAllowOverlay: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowDeviceAdmin: () -> Unit,
    onAllowBattery: () -> Unit,
    onTestUnlock: () -> Unit,
    onEndEarly: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val context = LocalContext.current
    val session = state.session

    val title: String
    val detail: String
    when {
        state.isPaused(now) -> {
            val until = DateFormat.getTimeFormat(context).format(Date(state.pausedUntil))
            title = "Paused"
            detail = "No prompts until $until."
        }

        session == null -> {
            title = "Guarding"
            detail = "You'll be asked for a reason on your next unlock."
        }

        session.isOverrun(now) -> {
            title = "Time's up"
            detail = "\u201C${session.reason}\u201D — ${session.elapsedMinutes(now)} min in."
        }

        else -> {
            title = "\u201C${session.reason}\u201D"
            detail = "${session.remainingMinutes(now)} min left."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    text = "TYDL",
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 6.sp,
                    color = Tide,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Time Your Digital Life.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mist,
                )
            }
            TextButton(onClick = onOpenSettings) { Text("⚙", color = Tide, fontSize = 20.sp) }
        }
        Spacer(Modifier.height(28.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "CURRENT SESSION",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 3.sp,
                    color = Mist,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Foam,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mist,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Last unlock seen: ${RelativeTime.describe(now, lastUnlockAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        if (state.isPaused(now)) {
            OutlinedButton(
                onClick = onResume,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Resume the guard")
            }
        } else {
            Text(
                text = "PAUSE",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = Mist,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { minutes ->
                    OutlinedButton(
                        onClick = { onPause(minutes) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${minutes}m")
                    }
                }
            }
        }

        if (session != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onEndEarly,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("End session early")
            }
        }

        if (!overlayGranted || !notificationsGranted || !deviceAdminGranted) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "SETUP",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = Mist,
            )
            Spacer(Modifier.height(8.dp))
            if (!overlayGranted) {
                SetupCard(
                    title = "Display over other apps",
                    body = "Without this, Tydl can only nag you with a notification instead of " +
                        "showing the prompt on top of whatever you opened.",
                    action = "Allow",
                    onClick = onAllowOverlay,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!notificationsGranted) {
                SetupCard(
                    title = "Notifications",
                    body = "The guard needs a visible notification to keep running in the background.",
                    action = "Allow",
                    onClick = onAllowNotifications,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!deviceAdminGranted) {
                SetupCard(
                    title = "Device admin",
                    body = "Required for \"I'm done\" to lock your screen. Without it, Tydl can end " +
                        "the session but cannot turn the screen off.",
                    action = "Allow",
                    onClick = onAllowDeviceAdmin,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "TROUBLESHOOTING",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 3.sp,
            color = Mist,
        )
        Spacer(Modifier.height(8.dp))
        if (!batteryExempt) {
            SetupCard(
                title = "Battery optimization",
                body = "Your phone is allowed to freeze Tydl to save power. Exempting it keeps the " +
                    "unlock guard alive.",
                action = "Allow",
                onClick = onAllowBattery,
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTestUnlock,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test the unlock prompt")
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Runs entirely on this device. Tydl has no internet permission.",
            style = MaterialTheme.typography.bodySmall,
            color = Mist,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsScreen(
    settings: ObsidianSettings,
    dailyStats: DailyStats,
    dailyStatsStore: DailyStatsStore,
    onBack: () -> Unit,
    onPickVault: () -> Unit,
    onPickTemplate: () -> Unit,
    onAddTile: () -> Unit,
    onViewLogs: () -> Unit,
    onSettingsChange: (ObsidianSettings) -> Unit,
) {
    var autoDeleteDays by remember { mutableStateOf(settings.dailyStatsAutoDeleteEveryNDays.toString()) }
    var deleteSaturday by remember { mutableStateOf(settings.dailyStatsAutoDeleteSaturday) }
    var deleteSunday by remember { mutableStateOf(settings.dailyStatsAutoDeleteSunday) }
    var radiusText by remember { mutableStateOf(settings.radiusMeters.toString()) }
    // Local copy so "Clear" can refresh the card without relying on a settings change to recompose.
    var stats by remember { mutableStateOf(dailyStats) }
    // Changing the rollover hour can move "today" to a different stored record.
    LaunchedEffect(settings.rolloverHour) {
        stats = dailyStatsStore.loadToday()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SETTINGS", style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp, color = Tide)
            TextButton(onClick = onBack) { Text("Back", color = Tide) }
        }
        Spacer(Modifier.height(24.dp))

        Text("OBSIDIAN", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = Mist)
        Spacer(Modifier.height(8.dp))
        SetupCard(
            title = "Daily notes folder",
            body = settings.vaultUri ?: "Not selected",
            action = "Choose",
            onClick = onPickVault,
        )
        Spacer(Modifier.height(10.dp))
        SetupCard(
            title = "Template file",
            body = settings.templateUri ?: "Not selected",
            action = "Choose",
            onClick = onPickTemplate,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = settings.filenameFormat,
            onValueChange = { onSettingsChange(settings.copy(filenameFormat = it.ifBlank { ObsidianSettings.DEFAULT_FILENAME_FORMAT })) },
            label = { Text("Daily note filename format") },
            supportingText = { Text("Example: DD-MM-YYYY") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = settings.rolloverHour.toString(),
            onValueChange = { v ->
                val h = v.filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 23) ?: settings.rolloverHour
                onSettingsChange(settings.copy(rolloverHour = h))
            },
            label = { Text("Rollover hour (0-23)") },
            supportingText = { Text("New day starts at this hour, e.g. 4 = 4 AM") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SetupCard(
                title = "Daily note tile",
                body = "Open today's note from Quick Settings, using Tydl's rollover hour.",
                action = "Add tile",
                onClick = onAddTile,
            )
            Spacer(Modifier.height(16.dp))
        }

        Text("TASK GATE", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = Mist)
        Spacer(Modifier.height(8.dp))
        io.github.serabin1.tydl.domain.obsidian.TaskGate.entries.forEach { gate ->
            val selected = settings.taskGate == gate
            OutlinedButton(
                onClick = { onSettingsChange(settings.copy(taskGate = gate)) },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (selected) Tide else Edge),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                val label = when (gate) {
                    io.github.serabin1.tydl.domain.obsidian.TaskGate.HARD -> "Hard — routine mandatory"
                    io.github.serabin1.tydl.domain.obsidian.TaskGate.SOFT -> "Soft — backlog only"
                    io.github.serabin1.tydl.domain.obsidian.TaskGate.MIXED -> "Mixed — 1 routine, 2 backlog"
                }
                Text(label, color = if (selected) Tide else Mist)
            }
        }

        Spacer(Modifier.height(32.dp))
        Text("BEHAVIOUR", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = Mist)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.awayModeEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(awayModeEnabled = it)) },
            )
            Column {
                Text("Away mode", style = MaterialTheme.typography.bodyMedium, color = Foam)
                Text("Silent when outside home radius", style = MaterialTheme.typography.bodySmall, color = Mist)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (settings.awayModeEnabled) {
            SetupCard(
                title = "Set home to current location",
                body = "Location capture is not wired yet; it ships with Away mode. The radius below is saved.",
                action = "Set",
                onClick = { /* location picking handled by Away mode; stubbed for now */ },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = radiusText,
                onValueChange = { v ->
                    radiusText = v.filter(Char::isDigit)
                    val r = radiusText.toIntOrNull()
                    if (r != null && r in 50..5000) onSettingsChange(settings.copy(radiusMeters = r))
                },
                label = { Text("Away radius (meters)") },
                supportingText = { Text("Hysteresis: ~300 m in, ~400 m out") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.gravitasEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(gravitasEnabled = it)) },
            )
            Column {
                Text("Gravitas screen", style = MaterialTheme.typography.bodyMedium, color = Foam)
                Text("Quote and reflections before a session starts", style = MaterialTheme.typography.bodySmall, color = Mist)
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.dailyStatsEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(dailyStatsEnabled = it)) },
            )
            Column {
                Text("Daily stats", style = MaterialTheme.typography.bodyMedium, color = Foam)
                Text("Per-day usage record stored on device", style = MaterialTheme.typography.bodySmall, color = Mist)
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.sleepTrackingEnabled,
                enabled = settings.dailyStatsEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(sleepTrackingEnabled = it)) },
            )
            Column {
                Text("Sleep tracking", style = MaterialTheme.typography.bodyMedium, color = Foam)
                Text("Detects sleep from screen-off gaps", style = MaterialTheme.typography.bodySmall, color = Mist)
            }
        }

        Spacer(Modifier.height(24.dp))

        if (settings.dailyStatsEnabled) {
            Text("DAILY STATS", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = Mist)
            Spacer(Modifier.height(8.dp))

            SetupCard(
                title = "Today (${stats.date})",
                body = "Sessions: ${stats.sessions}  •  ${stats.totalMinutes} min  •  " +
                    "Extensions: ${stats.extensionsCount} (${stats.extensionsMinutes} min)  •  " +
                    "Tasks done: ${stats.tasksDone}  •  Unlocks: ${stats.unlocks}" +
                    if (stats.sleepStart != null) "\nSleep: ${relativeSleep(stats)}" else "",
                action = "Clear",
                onClick = {
                    dailyStatsStore.clear(stats.date)
                    stats = dailyStatsStore.loadToday()
                },
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = autoDeleteDays,
                onValueChange = {
                    autoDeleteDays = it
                    val n = it.filter(Char::isDigit).toIntOrNull() ?: 0
                    onSettingsChange(settings.copy(
                        dailyStatsAutoDeleteEveryNDays = n,
                        dailyStatsAutoDeleteSaturday = deleteSaturday,
                        dailyStatsAutoDeleteSunday = deleteSunday,
                    ))
                },
                label = { Text("Auto-delete stats after N days (0 = off)") },
                supportingText = { Text("Or choose specific weekdays below") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = deleteSaturday,
                    onCheckedChange = { deleteSaturday = it;
                        onSettingsChange(settings.copy(
                            dailyStatsAutoDeleteEveryNDays = autoDeleteDays.filter(Char::isDigit).toIntOrNull() ?: 0,
                            dailyStatsAutoDeleteSaturday = it,
                            dailyStatsAutoDeleteSunday = deleteSunday,
                        ))
                    },
                )
                Text("Auto-delete on Saturdays", style = MaterialTheme.typography.bodySmall, color = Foam)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = deleteSunday,
                    onCheckedChange = { deleteSunday = it;
                        onSettingsChange(settings.copy(
                            dailyStatsAutoDeleteEveryNDays = autoDeleteDays.filter(Char::isDigit).toIntOrNull() ?: 0,
                            dailyStatsAutoDeleteSaturday = deleteSaturday,
                            dailyStatsAutoDeleteSunday = it,
                        ))
                    },
                )
                Text("Auto-delete on Sundays", style = MaterialTheme.typography.bodySmall, color = Foam)
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("DIAGNOSTICS", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = Mist)
        Spacer(Modifier.height(8.dp))
        SetupCard(
            title = "View logs",
            body = "Every decision Tydl made: unlocks, rollovers, prompts, and errors.",
            action = "View",
            onClick = onViewLogs,
        )
        Spacer(Modifier.height(40.dp))
    }
}

private fun relativeSleep(stats: DailyStats): String {
    val start = stats.sleepStart ?: return ""
    val wake = stats.wakeTime ?: start
    return SleepTracker.formatDuration(wake - start)
}

@Composable
private fun SetupCard(
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Foam,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
            )
            TextButton(onClick = onClick) {
                Text(action, color = Tide)
            }
        }
    }
}
