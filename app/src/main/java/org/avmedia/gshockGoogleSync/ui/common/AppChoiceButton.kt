package org.avmedia.gshockGoogleSync.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ButtonDefaults
import AppText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * One option of a "pick one" group:
 *  - selected: filled / highlighted (tapping does nothing),
 *  - selectable: outlined with white (light: normal) text,
 *  - [enabled] = false (not available, or a sync is running): greyed out.
 */
@Composable
fun AppChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false, // small pill (32 dp tall), for dense rows
) {
    val label = @Composable { color: Color ->
        AppText(text = text, color = color, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 16.sp, textAlign = TextAlign.Center)
    }
    val m = if (compact) modifier.height(32.dp) else modifier
    val pad = if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp) else ButtonDefaults.ContentPadding
    if (selected) {
        Button(onClick = {}, modifier = m, enabled = enabled, shape = CircleShape, contentPadding = pad) {
            label(if (enabled) MaterialTheme.colorScheme.onPrimary else Color.Unspecified)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, shape = CircleShape, contentPadding = pad) {
            val normal = if (isSystemInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
            label(if (enabled) normal else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    }
}
