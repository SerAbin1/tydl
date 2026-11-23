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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Ink
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

private enum class ExpiryMode { Choice, Extend, Done }

@Composable
fun ExpiryScreen(
    session: Session,
    now: Long,
    extensionsTodayCount: Int,
    extensionsTodayMinutes: Int,
    quote: String = "Consistency Before Greatness",
    task: String? = null,
    onTaskFinished: () -> Unit = {},
    onDone: (String) -> Unit,
    onStartAnother: (String) -> Unit = {},
    onExtend: (Int) -> Unit,
) {
    var mode by remember { mutableStateOf(ExpiryMode.Choice) }

    when (mode) {
        ExpiryMode.Extend -> ExtensionScreen(
            session = session,
            extensionsTodayCount = extensionsTodayCount,
            extensionsTodayMinutes = extensionsTodayMinutes,
            quote = quote,
            onExtend = { minutes ->
                onExtend(minutes)
            },
            onCancel = { mode = ExpiryMode.Choice },
        )

        ExpiryMode.Done -> DoneScreen(
            session = session,
            task = task,
            onTaskFinished = onTaskFinished,
            onDone = onDone,
            onStartAnother = onStartAnother,
        )

        ExpiryMode.Choice -> Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                text = "TIME'S UP",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 6.sp,
                color = Tide,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "\u201C${session.reason}\u201D",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Foam,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "You asked for ${session.requestedMinutes} minutes. " +
                    "It's been ${session.elapsedMinutes(now)}.",
                style = MaterialTheme.typography.bodyLarge,
                color = Mist,
            )
            if (session.extensionsUsed > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Extended ${session.extensionsUsed} of " +
                        "${Session.MAX_EXTENSIONS} times.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { mode = ExpiryMode.Done },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tide,
                    contentColor = Ink,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) {
                Text(
                    text = "I'm done",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (session.canExtend()) {
                val left = Session.MAX_EXTENSIONS - session.extensionsUsed
                OutlinedButton(
                    onClick = { mode = ExpiryMode.Extend },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = "Extend ($left left)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
                Text(
                    text = "No extensions left.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}