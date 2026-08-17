package com.atnip.seizuretracker.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens beyond Material3's [androidx.compose.material3.ColorScheme] — entry-type tags,
 * vet-role tags, avatar fallback colors, and the pine-dark "emphasis" text used for "· You" /
 * "· Current" suffixes. None of these map onto an existing ColorScheme role without overloading
 * an unrelated semantic meaning, so they live in their own CompositionLocal instead.
 */
data class AppColors(
    val pineDark: Color,
    val textSecondary: Color,
    val fieldPlaceholder: Color,
    val borderNeutral: Color,
    val borderDivider: Color,
    val avatarPrimary: Color,
    val avatarSecondary: Color,
    val seizureTagBg: Color,
    val seizureTagText: Color,
    val noteTagBg: Color,
    val noteTagText: Color,
    val vetRoleGeneralBg: Color,
    val vetRoleGeneralText: Color,
    val vetRoleEmergencyBg: Color,
    val vetRoleEmergencyText: Color,
    /** Tags render as a filled chip normally; high-contrast mode swaps to an outline instead. */
    val tagFilled: Boolean
)

val LightAppColors = AppColors(
    pineDark = PineDark,
    textSecondary = TextSecondary,
    fieldPlaceholder = FieldPlaceholder,
    borderNeutral = BorderNeutral,
    borderDivider = BorderDivider,
    avatarPrimary = AvatarPrimary,
    avatarSecondary = AvatarSecondary,
    seizureTagBg = SeizureTagBg,
    seizureTagText = SeizureTagText,
    noteTagBg = NoteTagBg,
    noteTagText = NoteTagText,
    vetRoleGeneralBg = VetRoleGeneralBg,
    vetRoleGeneralText = VetRoleGeneralText,
    vetRoleEmergencyBg = VetRoleEmergencyBg,
    vetRoleEmergencyText = VetRoleEmergencyText,
    tagFilled = true
)

val HighContrastAppColors = AppColors(
    pineDark = HighContrastText,
    textSecondary = HighContrastText,
    fieldPlaceholder = HighContrastText,
    borderNeutral = HighContrastBorder,
    borderDivider = HighContrastBorder,
    avatarPrimary = HighContrastText,
    avatarSecondary = HighContrastText,
    seizureTagBg = Color.Transparent,
    seizureTagText = SeizureTagText,
    noteTagBg = Color.Transparent,
    noteTagText = NoteTagText,
    vetRoleGeneralBg = Color.Transparent,
    vetRoleGeneralText = HighContrastText,
    vetRoleEmergencyBg = Color.Transparent,
    vetRoleEmergencyText = VetRoleEmergencyText,
    tagFilled = false
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
