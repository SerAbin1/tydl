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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun ExpiryScreen(
    session: Session,
    now: Long,
    onDone: () -> Unit,
    onExtend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
        Text(
            text = "\u201C${session.reason}\u201D",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = Foam,
        )
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

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onDone,
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
                onClick = onExtend,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text(
                    text = "+5 minutes ($left left)",
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
