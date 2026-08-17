package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.ui.theme.PillShape

/** Full-pill filled CTA — 44dp tall, matching the design tokens' primary button. */
@Composable
fun PrimaryPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.height(44.dp), enabled = enabled, shape = PillShape) {
        Text(text)
    }
}

/** Full-pill outline CTA — 40dp tall, matching the design tokens' secondary button. */
@Composable
fun OutlinePillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(40.dp), enabled = enabled, shape = PillShape) {
        Text(text)
    }
}
