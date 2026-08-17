package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.ui.theme.LocalAppColors
import com.atnip.seizuretracker.ui.theme.PillShape

enum class EntryType { SEIZURE, NOTE }

/**
 * SEIZURE = filled circle icon + red family; NOTE = filled rounded-square icon + slate-blue
 * family (deliberately not green) — shape and color always pair, per the "never color alone"
 * accessibility rule. Flips from a filled chip to an outline in high-contrast mode.
 */
@Composable
fun EntryTypeTag(type: EntryType, modifier: Modifier = Modifier) {
    val appColors = LocalAppColors.current
    val bg = if (type == EntryType.SEIZURE) appColors.seizureTagBg else appColors.noteTagBg
    val text = if (type == EntryType.SEIZURE) appColors.seizureTagText else appColors.noteTagText
    val label = if (type == EntryType.SEIZURE) "SEIZURE" else "NOTE"

    val tagModifier = if (appColors.tagFilled) {
        modifier.background(bg, PillShape)
    } else {
        modifier.border(1.5.dp, text, PillShape)
    }

    Row(
        modifier = tagModifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (type) {
            EntryType.SEIZURE -> Box(Modifier.size(8.dp).background(text, CircleShape))
            EntryType.NOTE -> Box(Modifier.size(8.dp).background(text, RoundedCornerShape(2.dp)))
        }
        Text(
            text = label,
            color = text,
            fontWeight = if (appColors.tagFilled) FontWeight.SemiBold else FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
