package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Matches the design tokens' 1px neutral-border / 4dp-radius field. Uses Material3's own `label`
 * slot rather than a separate standalone [Text] above the field — a separate label breaks
 * `onNodeWithText(label).performTextInput(...)` in Compose UI tests, since the field's actual
 * focusable node ends up disjoint from the label node even with `mergeDescendants`.
 */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth()
    )
}
