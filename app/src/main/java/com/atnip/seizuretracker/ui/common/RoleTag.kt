package com.atnip.seizuretracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atnip.seizuretracker.data.model.VetRoles
import com.atnip.seizuretracker.ui.theme.LocalAppColors
import com.atnip.seizuretracker.ui.theme.PillShape

/**
 * A pet/role chip in the vets directory, e.g. "Bear · General" — role is always spelled out in
 * text, never color-only. Only "Emergency" gets the warm-tinted pair; every other role (General,
 * Neuro specialist, Other) shares one neutral style.
 */
@Composable
fun RoleTag(petName: String, role: String, modifier: Modifier = Modifier) {
    val appColors = LocalAppColors.current
    val isEmergency = role == VetRoles.ALL[1]
    val bg = if (isEmergency) appColors.vetRoleEmergencyBg else appColors.vetRoleGeneralBg
    val text = if (isEmergency) appColors.vetRoleEmergencyText else appColors.vetRoleGeneralText
    Text(
        text = "$petName · $role",
        color = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(bg, PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
