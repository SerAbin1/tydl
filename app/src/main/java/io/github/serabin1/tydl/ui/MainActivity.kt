package io.github.serabin1.tydl.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.serabin1.tydl.data.GuardStore
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.guard.GuardService
import io.github.serabin1.tydl.guard.LockAdminReceiver
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

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GuardService.start(this)
        fallback = fallbackFrom(intent)
        setContent {
            TydlTheme {
                when (fallback) {
                    GuardService.EXTRA_SHOW_PROMPT -> PromptScreen(
                        onStart = { reason, minutes ->
                            GuardService.startSession(this, reason, minutes)
                            fallback = null
                            refresh()
                        },
                    )

                    GuardService.EXTRA_SHOW_EXPIRY -> {
                        val session = guardState.session
                        if (session != null) {
                            ExpiryScreen(
                                session = session,
                                now = System.currentTimeMillis(),
                                onDone = {
                                    GuardService.endAndLock(this)
                                    fallback = null
                                    refresh()
                                },
                                onExtend = {
                                    GuardService.extend(this)
                                    fallback = null
                                    refresh()
                                },
                            )
                        } else {
                            fallback = null
                            MainContent()
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
            onOpenAppSettings = ::openAppSettings,
            onTestUnlock = { GuardService.simulateUnlock(this) },
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
    onOpenAppSettings: () -> Unit,
    onTestUnlock: () -> Unit,
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Foam,
                )
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
        SetupCard(
            title = "Nothing appeared on unlock?",
            body = "ColorOS and OxygenOS kill background apps unless you allow them. In the app " +
                "settings, enable Auto-launch and Allow background activity.",
            action = "Open app settings",
            onClick = onOpenAppSettings,
        )
        if (!batteryExempt) {
            Spacer(Modifier.height(10.dp))
            SetupCard(
                title = "Battery optimization",
                body = "Your phone is allowed to freeze Tydl to save power. Exempting it keeps the " +
                    "unlock guard alive.",
                action = "Allow",
                onClick = onAllowBattery,
            )
        }
        Spacer(Modifier.height(10.dp))
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
