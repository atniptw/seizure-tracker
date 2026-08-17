package com.atnip.seizuretracker.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.ui.common.AppBottomSheet
import com.atnip.seizuretracker.ui.theme.LocalAppColors

/**
 * Screen 2 — the single "+" entry point's quick-choice sheet (Seizure vs. Health note). This was
 * A/B'd against a dual-FAB in the design handoff and the unified sheet won even though it costs
 * one extra tap on seizure logs — do not reintroduce a dual-FAB without re-validating that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    petName: String,
    onSeizure: () -> Unit,
    onHealthNote: () -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss) {
        Text("New entry for $petName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAddOption(
                title = "Seizure",
                subtitle = "Same fast form as always",
                chipColor = LocalAppColors.current.seizureTagBg,
                onClick = { onSeizure(); onDismiss() }
            ) {
                Box(Modifier.size(12.dp).background(LocalAppColors.current.seizureTagText, CircleShape))
            }
            QuickAddOption(
                title = "Other / health note",
                subtitle = "Anything else worth mentioning",
                chipColor = LocalAppColors.current.noteTagBg,
                onClick = { onHealthNote(); onDismiss() }
            ) {
                Box(Modifier.size(12.dp).background(LocalAppColors.current.noteTagText, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun QuickAddOption(
    title: String,
    subtitle: String,
    chipColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(chipColor, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
