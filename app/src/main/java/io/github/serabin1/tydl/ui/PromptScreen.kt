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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.Reasons
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Ink
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

fun promptMinutes(text: String): Int? =
    text.toIntOrNull()?.takeIf { it in Session.MIN_MINUTES..Session.MAX_MINUTES }

@Composable
fun PromptScreen(
    initialReason: String = "",
    onStart: (String, Int) -> Unit,
) {
    var reasonText by remember(initialReason) { mutableStateOf(initialReason) }
    var minutesText by remember { mutableStateOf("") }

    val reason = Reasons.accepted(reasonText, preFilled = initialReason.isNotBlank())
    val minutes = promptMinutes(minutesText)

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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Foam,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Name the reason. Set the time. Then stick to it.",
            style = MaterialTheme.typography.bodyLarge,
            color = Mist,
        )
        Spacer(Modifier.height(36.dp))
        OutlinedTextField(
            value = reasonText,
            onValueChange = { reasonText = it },
            label = { Text("Reason") },
            supportingText = { Text("At least 4 words: why you're opening your phone.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = minutesText,
            onValueChange = { minutesText = it.filter(Char::isDigit).take(3) },
            label = { Text("Minutes") },
            supportingText = {
                Text("${Session.MIN_MINUTES}-${Session.MAX_MINUTES} minutes")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val chosenReason = reason ?: return@Button
                val chosenMinutes = minutes ?: return@Button
                onStart(chosenReason, chosenMinutes)
            },
            enabled = reason != null && minutes != null,
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
                text = minutes?.let { "Start $it min" } ?: "Start",
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
