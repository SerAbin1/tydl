package io.github.serabin1.tydl.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.log.LogEntry
import io.github.serabin1.tydl.domain.log.LogFormat
import io.github.serabin1.tydl.domain.log.LogTag
import io.github.serabin1.tydl.ui.theme.Alarm
import io.github.serabin1.tydl.ui.theme.Edge
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

/**
 * The decision history, newest first. Shows names, counts, and outcomes, never the reason text,
 * task text, or reflection the user wrote.
 */
@Composable
fun LogsScreen(
    entries: List<LogEntry>,
    vaultConfigured: Boolean,
    todayNote: String,
    rolloverHour: Int,
    serviceRunning: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "LOGS",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
                color = Tide,
            )
            TextButton(onClick = onBack) { Text("Back", color = Tide) }
        }
        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Edge),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                SummaryRow("Vault", if (vaultConfigured) "Configured" else "Not configured")
                SummaryRow("Today's note", todayNote)
                SummaryRow("Rollover hour", "%02d:00".format(rolloverHour))
                SummaryRow("Guard service", if (serviceRunning) "Running" else "Stopped")
                SummaryRow("Entries", entries.size.toString())
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onShare) { Text("Share", color = Tide) }
            TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Alarm) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = "No logs yet. Unlock the phone or start a session; every decision " +
                            "will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mist,
                    )
                }
            } else {
                items(entries.asReversed()) { entry -> LogRow(entry) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear logs?") },
            text = {
                Text("This deletes the log file on this device. It cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("Clear", color = Alarm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Mist)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Foam)
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = LogFormat.timestamp(entry.at),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Mist,
            )
            Text(
                text = entry.tag.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = tagColor(entry.tag),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = Foam,
        )
    }
}

private fun tagColor(tag: LogTag): Color = when (tag) {
    LogTag.ERROR -> Alarm
    LogTag.ROLLOVER -> Tide
    LogTag.SESSION -> Tide
    else -> Mist
}
