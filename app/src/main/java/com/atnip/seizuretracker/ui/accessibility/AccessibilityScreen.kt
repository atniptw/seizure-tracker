package com.atnip.seizuretracker.ui.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityScreen(navController: NavController, viewModel: AccessibilityViewModel) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("How we handle color", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Entry-type tags never rely on hue alone: each pairs a shape (circle vs. " +
                    "square), an icon, and a text label with its color, and we swapped the " +
                    "health-note tag off green — red/green is the single worst pairing for " +
                    "red-green color blindness, which covers the large majority of color " +
                    "vision deficiency. All text and icon colors are checked against WCAG's " +
                    "4.5:1 (body) / 3:1 (large text, icons) minimum contrast.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(20.dp))

            ToggleRow(
                title = "High-contrast mode",
                description = "Darker text, stronger borders, no reliance on subtle tints.",
                checked = settings.highContrast,
                onCheckedChange = viewModel::setHighContrast
            )
            Spacer(Modifier.height(20.dp))
            ToggleRow(
                title = "Larger text",
                description = "Scales body copy and labels up for easier reading.",
                checked = settings.largerText,
                onCheckedChange = viewModel::setLargerText
            )
            Spacer(Modifier.height(20.dp))
            ToggleRow(
                title = "Reduce motion",
                description = "Turns off transitions and animated indicators.",
                checked = settings.reduceMotion,
                onCheckedChange = viewModel::setReduceMotion
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
