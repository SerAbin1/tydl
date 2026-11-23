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
import androidx.compose.material3.OutlinedTextField
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
import io.github.serabin1.tydl.domain.DoneOutcome
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Ink
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

@Composable
fun DoneScreen(
    session: Session?,
    task: String? = null,
    onTaskFinished: () -> Unit = {},
    onDone: (String) -> Unit,
) {
    var askFinish by remember { mutableStateOf(task != null) }
    var noteText by remember { mutableStateOf("") }
    val note = DoneOutcome.note(noteText)

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
            text = if (askFinish) "DID YOU FINISH IT?" else "HOW DID IT GO?",
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
                text = session?.let { "\u201C${it.reason}\u201D" } ?: "Session ended.",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = Foam,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (askFinish) {
                "Did you finish the task?"
            } else {
                "A line or two. Then we close."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Mist,
        )
        Spacer(Modifier.height(36.dp))
        if (askFinish) {
            Button(
                onClick = {
                    askFinish = false
                    onTaskFinished()
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tide,
                    contentColor = Ink,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) {
                Text("Yes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { askFinish = false },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text("No", style = MaterialTheme.typography.titleMedium, color = Tide)
            }
            Spacer(Modifier.height(40.dp))
        } else {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("How it went") },
                supportingText = { Text("Required.") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { note?.let(onDone) },
                enabled = note != null,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tide, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Text("Close", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
