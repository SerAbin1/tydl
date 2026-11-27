package io.github.serabin1.tydl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.obsidian.ObsidianTask
import io.github.serabin1.tydl.domain.obsidian.ObsidianNotes
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

@Composable
fun TasksScreen(
    tasks: List<ObsidianTask>,
    todayTasksDone: Int? = null,
    yesterdayTasksDone: Int? = null,
    onTask: (ObsidianTask) -> Unit,
    onSomethingElse: () -> Unit,
    onJustTwoMinutes: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = "WHAT ARE YOU DOING?",
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 6.sp,
            color = Tide,
        )
        if (todayTasksDone != null) {
            Spacer(Modifier.height(12.dp))
            TasksStatsLine(todayTasksDone = todayTasksDone, yesterdayTasksDone = yesterdayTasksDone)
        }
        Spacer(Modifier.height(24.dp))
        tasks.forEach { task ->
            OutlinedButton(
                onClick = { onTask(task) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            ) {
                Text(
                    text = ObsidianNotes.display(task),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Tide,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onSomethingElse,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Something else", style = MaterialTheme.typography.titleSmall, color = Tide)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onJustTwoMinutes,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Just 2 mins", style = MaterialTheme.typography.titleSmall, color = Tide)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * "Finished x tasks today, y more than yesterday's z" — or the sad-puppy line when today trails
 * yesterday. When yesterday's data is missing the comparison is hidden (never shown as 0), per the
 * safety rules.
 */
@Composable
private fun TasksStatsLine(todayTasksDone: Int, yesterdayTasksDone: Int?) {
    val text = when {
        yesterdayTasksDone == null ->
            "Finished $todayTasksDone task${if (todayTasksDone == 1) "" else "s"} today."

        todayTasksDone > yesterdayTasksDone -> {
            val more = todayTasksDone - yesterdayTasksDone
            "Finished $todayTasksDone tasks today, $more more than yesterday's $yesterdayTasksDone."
        }

        todayTasksDone == yesterdayTasksDone ->
            "Finished $todayTasksDone tasks today, same as yesterday's $yesterdayTasksDone."

        else -> "\uD83D\uDC36 Finished $todayTasksDone tasks today, fewer than yesterday's $yesterdayTasksDone."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Mist,
    )
}
