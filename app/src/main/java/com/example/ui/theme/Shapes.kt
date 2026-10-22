package com.example.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Requirement A:
// - Shapes: cards 16 dp, sheets 28 dp top corners, map buttons 48 dp circles with shadow
// - Spacing on a 4 dp grid, minimum touch target 48 dp
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp), // 16 dp on cards
    large = RoundedCornerShape(28.dp),  // 28 dp on sheets
    extraLarge = RoundedCornerShape(28.dp)
)

val CardShape = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
val SheetTopShape = SheetShape
val ButtonShape = RoundedCornerShape(16.dp)
val MapButtonShape = CircleShape
val MapButtonSize = 48.dp
val MapButtonShadowElevation = 6.dp
val MinTouchTargetSize = 48.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    // 4 dp grid step aliases
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
}
