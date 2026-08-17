package com.atnip.seizuretracker.ui.theme

import androidx.compose.ui.graphics.Color

// Pine — primary accent (from the "Pet Diary Revamp" design handoff: oklch(45% 0.08 160)).
val Pine = Color(0xFF256346)
val PineDark = Color(0xFF0A3723) // oklch(30% 0.06 160) — "· You" / "· Current" emphasis text
val PineLightTint = Color(0xFF9DCAB1) // oklch(80% 0.06 160) — hero/summary card background

val WarmBackground = Color(0xFFFAF9F6)
val CardSurface = Color(0xFFFFFFFF)
val ShellAlt = Color(0xFFEDEBE6)

val TextPrimary = Color(0xFF1C1B1F)
val TextSecondary = Color(0xFF5A5A55)
val FieldPlaceholder = Color(0xFF49454F)

val BorderNeutral = Color(0xFF79747E)
val BorderDivider = Color(0xFFD6D3CC)

// Avatar fallback fill is semantic (primary vs. secondary entity), never a stored per-person color.
val AvatarPrimary = Color(0xFF49454F)
val AvatarSecondary = Color(0xFF79747E)

val AlertRed = Color(0xFFB3261E) // delete/remove actions and the seizure tag only — never a second brand color

val SeizureTagBg = Color(0xFFF4D9D6)
val SeizureTagText = AlertRed
val NoteTagBg = Color(0xFFDCE4EE)
val NoteTagText = Color(0xFF33475A) // deliberately not green — avoids red/green color-blind pairing

val VetRoleGeneralBg = ShellAlt
val VetRoleGeneralText = TextPrimary
val VetRoleEmergencyBg = SeizureTagBg
val VetRoleEmergencyText = Color(0xFF8A1F19) // distinct from both SeizureTagText and AlertRed

// High-contrast mode: pure white/black, no soft tints.
val HighContrastBackground = Color(0xFFFFFFFF)
val HighContrastText = Color(0xFF000000)
val HighContrastBorder = Color(0xFF000000)
