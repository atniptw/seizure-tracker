package com.atnip.seizuretracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp), // cards
    medium = RoundedCornerShape(12.dp), // cards
    large = RoundedCornerShape(20.dp) // dialogs / bottom sheets
)

/** Full pill shape for buttons/tags/chips — percent-based since pill height varies (36/40/44dp), so a fixed dp radius would be wrong at every size. */
val PillShape = RoundedCornerShape(percent = 50)
