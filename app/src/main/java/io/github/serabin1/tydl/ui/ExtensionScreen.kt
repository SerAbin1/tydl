package io.github.serabin1.tydl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.theme.Foam
import io.github.serabin1.tydl.ui.theme.Ink
import io.github.serabin1.tydl.ui.theme.Mist
import io.github.serabin1.tydl.ui.theme.Tide

@Composable
fun ExtensionScreen(
    session: Session,
    extensionsTodayCount: Int,
    extensionsTodayMinutes: Int,
    quote: String = "Consistency Before Greatness",
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var minutesText by remember { mutableStateOf("") }
    val minutes = promptMinutes(minutesText)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            text = quote.uppercase(),
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
        Spacer(Modifier.height(14.dp))
        Text(
            text = "You asked for ${session.requestedMinutes} minutes. " +
                "It's been ${session.elapsedMinutes(System.currentTimeMillis())}.",
            style = MaterialTheme.typography.bodyLarge,
            color = Mist,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Extensions today: $extensionsTodayCount " +
                "(${extensionsTodayMinutes} min total).",
            style = MaterialTheme.typography.bodySmall,
            color = Mist,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = minutesText,
            onValueChange = { minutesText = it.filter(Char::isDigit).take(3) },
            label = { Text("Extension minutes") },
            supportingText = { Text("${Session.MIN_MINUTES}-${Session.MAX_MINUTES} minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { minutes?.let(onExtend) },
            enabled = minutes != null,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tide, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            Text("Extend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Cancel", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(40.dp))
    }
}