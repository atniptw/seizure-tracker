package com.atnip.seizuretracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

/** Matches the design handoff's larger-text examples (14→17, 18→22 ≈ 1.21–1.22x). */
const val LARGE_TEXT_SCALE = 1.22f

/** Scales every set font size in this [Typography] by [factor], leaving weight/other properties untouched. */
fun Typography.scaled(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaledSize(factor),
    displayMedium = displayMedium.scaledSize(factor),
    displaySmall = displaySmall.scaledSize(factor),
    headlineLarge = headlineLarge.scaledSize(factor),
    headlineMedium = headlineMedium.scaledSize(factor),
    headlineSmall = headlineSmall.scaledSize(factor),
    titleLarge = titleLarge.scaledSize(factor),
    titleMedium = titleMedium.scaledSize(factor),
    titleSmall = titleSmall.scaledSize(factor),
    bodyLarge = bodyLarge.scaledSize(factor),
    bodyMedium = bodyMedium.scaledSize(factor),
    bodySmall = bodySmall.scaledSize(factor),
    labelLarge = labelLarge.scaledSize(factor),
    labelMedium = labelMedium.scaledSize(factor),
    labelSmall = labelSmall.scaledSize(factor)
)

private fun TextStyle.scaledSize(factor: Float): TextStyle =
    if (fontSize.isSpecified) copy(fontSize = (fontSize.value * factor).sp) else this
