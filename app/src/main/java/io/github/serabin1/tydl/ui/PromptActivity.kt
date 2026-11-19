package io.github.serabin1.tydl.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import io.github.serabin1.tydl.guard.GuardService
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Ink
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.TydlTheme
import io.github.serabin1.tydl.ui.theme.Tide

/**
 * Shown on every unlock when no session is running. Not dismissible with Back; Home is the
 * only way out, which is the friction the whole app is made of.
 */
class PromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TydlTheme {
                PromptScreen(
                    onStart = { reason ->
                        GuardService.startSession(this, reason)
                        finish()
                    },
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) { /* Home is the only exit */ }
    }

    override fun onResume() {
        super.onResume()
        GuardService.markShown(this)
    }
}

private val PRESETS = listOf("Email", "Message", "Look something up", "Maps", "Feed")
private const val OTHER = "Other"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptScreen(onStart: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    val trimmedNote = note.trim()
    val reason = when {
        selected == null -> null
        selected == OTHER -> trimmedNote.ifBlank { null }
        trimmedNote.isEmpty() -> selected
        else -> "$selected — $trimmedNote"
    }

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
            text = "TYDL",
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 6.sp,
            color = Tide,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "State your reason.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = Foam,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Five minutes. Then Tydl asks you to stop.",
            style = MaterialTheme.typography.bodyLarge,
            color = Mist,
        )
        Spacer(Modifier.height(36.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (PRESETS + OTHER).forEach { preset ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { selected = preset },
                    label = { Text(preset) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Tide,
                        selectedLabelColor = Ink,
                    ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Add a note (optional)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = { reason?.let(onStart) },
            enabled = reason != null,
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
                text = if (reason == null) "Start" else "Start five minutes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "The clock starts when you tap Start.",
            style = MaterialTheme.typography.bodySmall,
            color = Mist,
        )
        Spacer(Modifier.height(32.dp))
    }
}
