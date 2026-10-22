package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Requirement A:
// One type scale:
// - display: 28 sp bold (screen titles)
// - title: 20 sp semibold (trail names)
// - body: 16 sp
// - label: 13 sp
// Numbers in stats use tabular figures ("tnum").

const val TABULAR_FIGURES_FEATURE = "tnum"

val DisplayTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val TitleTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val BodyTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val BodySemiBoldTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val LabelTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val TabularStatsTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    fontFeatureSettings = TABULAR_FIGURES_FEATURE
)

val TabularBodyStyle = TabularStatsTextStyle
val TabularLabelStyle = LabelTextStyle

val Typography = Typography(
    displayLarge = DisplayTextStyle,
    displayMedium = DisplayTextStyle,
    displaySmall = DisplayTextStyle,
    headlineLarge = DisplayTextStyle, // 28 sp bold (screen titles)
    headlineMedium = TitleTextStyle,  // 20 sp semibold (trail names)
    headlineSmall = TitleTextStyle,
    titleLarge = TitleTextStyle,      // 20 sp semibold (trail names)
    titleMedium = BodySemiBoldTextStyle, // 16 sp semibold (stats)
    titleSmall = LabelTextStyle.copy(fontWeight = FontWeight.SemiBold), // 13 sp
    bodyLarge = BodyTextStyle,        // 16 sp
    bodyMedium = BodyTextStyle,       // 16 sp
    bodySmall = LabelTextStyle,       // 13 sp
    labelLarge = LabelTextStyle.copy(fontWeight = FontWeight.SemiBold), // 13 sp
    labelMedium = LabelTextStyle,     // 13 sp
    labelSmall = LabelTextStyle       // 13 sp
)
