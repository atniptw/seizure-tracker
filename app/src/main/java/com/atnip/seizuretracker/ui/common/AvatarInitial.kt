package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.ui.theme.LocalAppColors

/**
 * Initial-letter fallback avatar. [isPrimary] selects the fill color — "the currently active pet"
 * or "you" vs. everyone else — a computed state derived by the caller, never a color stored per
 * entity (per the design handoff: no per-item hue-coding).
 */
@Composable
fun AvatarInitial(name: String, isPrimary: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val appColors = LocalAppColors.current
    val bg = if (isPrimary) appColors.avatarPrimary else appColors.avatarSecondary
    Box(
        modifier = modifier.size(size).background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
