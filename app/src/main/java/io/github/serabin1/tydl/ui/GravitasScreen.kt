package io.github.serabin1.tydl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.Gravitas
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide
import kotlinx.coroutines.delay

/**
 * The Gravitas screen: a quote, yesterday's reflections, today's finished tasks, and last night's
 * sleep, shown for [Gravitas.durationMs] before the session starts. Not skippable — there is no
 * skip control — and the shell only routes here when [Gravitas.shouldShow] is true.
 */
@Composable
fun GravitasScreen(
    requestedMinutes: Int,
    quote: String,
    yesterdayReflections: String?,
    todayFinishedTasks: List<String>,
    lastNightSleep: String?,
    onFinished: () -> Unit,
) {
    // Countdown driven by the pure duration; onFinished fires once it elapses.
    var remaining by remember { mutableStateOf((Gravitas.durationMs(requestedMinutes) / 1000).toInt()) }
    LaunchedEffect(requestedMinutes) {
        val totalMs = Gravitas.durationMs(requestedMinutes)
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            val left = ((totalMs - elapsed).coerceAtLeast(0L) / 1000).toInt()
            remaining = left
            if (elapsed >= totalMs) {
                onFinished()
                break
            }
            delay(250)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            text = "SETTLE IN",
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 6.sp,
            color = Tide,
        )
        Spacer(Modifier.height(20.dp))

        if (quote.isNotBlank()) {
            Text(
                text = "\u201C$quote\u201D",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Foam,
            )
            Spacer(Modifier.height(24.dp))
        }

        if (!yesterdayReflections.isNullOrBlank()) {
            Text(
                text = "YESTERDAY",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = Mist,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = yesterdayReflections,
                style = MaterialTheme.typography.bodyMedium,
                color = Foam,
            )
            Spacer(Modifier.height(20.dp))
        }

        if (todayFinishedTasks.isNotEmpty()) {
            Text(
                text = "DONE TODAY",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = Mist,
            )
            Spacer(Modifier.height(6.dp))
            todayFinishedTasks.forEach { task ->
                Text(
                    text = "• $task",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Foam,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        if (!lastNightSleep.isNullOrBlank()) {
            Text(
                text = "LAST NIGHT",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = Mist,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = lastNightSleep,
                style = MaterialTheme.typography.bodyMedium,
                color = Foam,
            )
            Spacer(Modifier.height(20.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = if (remaining > 0) "Starting in ${remaining}s\u2026" else "Starting\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = Mist,
        )
        Spacer(Modifier.height(40.dp))
    }
}