package com.atnip.seizuretracker.ui.accessibility

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Three independent, user-toggleable, device-local accessibility modes — a user can have all
 * three on at once. [largerText] defaults on, matching the design handoff's stated default.
 */
data class A11ySettings(
    val highContrast: Boolean = false,
    val largerText: Boolean = true,
    val reduceMotion: Boolean = false
)

val LocalA11ySettings = staticCompositionLocalOf { A11ySettings() }
