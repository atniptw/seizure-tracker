package com.atnip.seizuretracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.atnip.seizuretracker.ui.accessibility.A11ySettings
import com.atnip.seizuretracker.ui.accessibility.LocalA11ySettings

// Every role is set explicitly (not just the ones a screen happens to reference today) —
// Material3 fills any unset role with its own default purple-leaning baseline palette, which
// would otherwise leak through on components this app hasn't touched yet (e.g. SegmentedButton's
// selected fill uses secondaryContainer, not primary/primaryContainer).
private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    primaryContainer = PineLightTint,
    onPrimaryContainer = PineDark,
    secondary = PineLightTint,
    onSecondary = PineDark,
    secondaryContainer = PineLightTint,
    onSecondaryContainer = PineDark,
    tertiary = Pine,
    onTertiary = Color.White,
    tertiaryContainer = PineLightTint,
    onTertiaryContainer = PineDark,
    error = AlertRed,
    background = WarmBackground,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = ShellAlt,
    onSurfaceVariant = TextSecondary,
    outline = BorderNeutral,
    outlineVariant = BorderDivider
)

private val HighContrastColors = lightColorScheme(
    primary = HighContrastText,
    onPrimary = HighContrastBackground,
    primaryContainer = HighContrastBackground,
    onPrimaryContainer = HighContrastText,
    secondary = HighContrastText,
    onSecondary = HighContrastBackground,
    secondaryContainer = HighContrastBackground,
    onSecondaryContainer = HighContrastText,
    tertiary = HighContrastText,
    onTertiary = HighContrastBackground,
    tertiaryContainer = HighContrastBackground,
    onTertiaryContainer = HighContrastText,
    error = AlertRed,
    background = HighContrastBackground,
    onBackground = HighContrastText,
    surface = HighContrastBackground,
    onSurface = HighContrastText,
    surfaceVariant = HighContrastBackground,
    onSurfaceVariant = HighContrastText,
    outline = HighContrastBorder,
    outlineVariant = HighContrastBorder
)

@Composable
fun SeizureTrackerTheme(a11y: A11ySettings = A11ySettings(), content: @Composable () -> Unit) {
    val colorScheme = if (a11y.highContrast) HighContrastColors else LightColors
    val typography = if (a11y.largerText) AppTypography.scaled(LARGE_TEXT_SCALE) else AppTypography
    val appColors = if (a11y.highContrast) HighContrastAppColors else LightAppColors

    CompositionLocalProvider(
        LocalA11ySettings provides a11y,
        LocalAppColors provides appColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}
