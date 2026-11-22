package io.github.serabin1.tydl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

@Composable
fun EarlyEndScreen(
    session: Session,
    task: String? = null,
    onStartAnother: () -> Unit,
    onTaskFinished: () -> Unit = {},
    onDone: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(false) }

    if (mode) {
        DoneScreen(
            session = session,
            task = task,
            onTaskFinished = onTaskFinished,
            onDone = onDone,
        )
    } else {
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
                text = "END EARLY?",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 6.sp,
                color = Tide,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "\u201C${session.reason}\u201D",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = Foam,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "You still have ${session.remainingMinutes(System.currentTimeMillis())} min left.",
                style = MaterialTheme.typography.bodyLarge,
                color = Mist,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onStartAnother,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tide, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Text("Start another session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { mode = true },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("I'm done", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}