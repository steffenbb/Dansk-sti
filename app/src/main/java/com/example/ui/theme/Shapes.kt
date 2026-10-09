package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp), // 16 dp on cards
    large = RoundedCornerShape(28.dp),  // 28 dp on sheets
    extraLarge = RoundedCornerShape(28.dp)
)

val CardShape = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
val ButtonShape = RoundedCornerShape(16.dp)
