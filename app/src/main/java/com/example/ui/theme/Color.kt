package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Requirement A: Design system colour tokens
// Primary forest green #2E6B3F, primary container #DCEFE0
val ForestGreenPrimary = Color(0xFF2E6B3F)
val ForestGreenDark = Color(0xFF1F4B2C)
val ForestGreenLight = Color(0xFF3E8451)
val ForestGreenContainerLight = Color(0xFFDCEFE0)
val ForestGreenOnContainerLight = Color(0xFF0E2916)

// Dark theme greens (high contrast on #121614 / #1B201D)
val ForestGreenDarkPrimary = Color(0xFF66BB7E)
val ForestGreenDarkContainer = Color(0xFF1E462A)
val ForestGreenOnDarkContainer = Color(0xFFDCEFE0)

// Surfaces & Backgrounds
// Light: white (#FFFFFF) / #F6F7F5
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFF6F7F5)
val BackgroundLight = Color(0xFFF6F7F5)
val CardBackgroundLight = Color(0xFFFFFFFF)

// Dark: #121614 / #1B201D
val SurfaceDark = Color(0xFF121614)
val SurfaceVariantDark = Color(0xFF1B201D)
val BackgroundDark = Color(0xFF121614)
val CardBackgroundDark = Color(0xFF1B201D)

// High-contrast text (at least 4.5:1 contrast ratio)
val TextPrimaryLight = Color(0xFF121614)
val TextSecondaryLight = Color(0xFF424A45)
val TextMutedLight = Color(0xFF5D6660)

val TextPrimaryDark = Color(0xFFF0F4F1)
val TextSecondaryDark = Color(0xFFB5C0B9)
val TextMutedDark = Color(0xFF8C9790)

// Semantic Accents: Accent amber #F2A33A (selected tool, warnings), Error #C62828
val AccentAmber = Color(0xFFF2A33A)
val WarningAmber = Color(0xFFF2A33A)
val AmberAccent = Color(0xFFF2A33A)
val AmberTrail = Color(0xFFF2A33A)
val AmberLight = Color(0xFFFFF3E0)

val ErrorRed = Color(0xFFC62828)
val AlertRed = Color(0xFFC62828)
val AlertRedContainer = Color(0xFFFFEBEE)
val OffTrailAlert = Color(0xFFC62828)
val OffRouteRed = Color(0xFFC62828)
val SosRed = Color(0xFFC62828)

val NordicBlue = Color(0xFF0277BD)
val NordicSky = Color(0xFF29B6F6)
val WaterBlue = Color(0xFF0277BD)
val ShelterOrange = Color(0xFFD84315)
val ShelterBrown = Color(0xFFD84315)

val BorderSubtleLight = Color(0xFFDDE2DE)
val BorderSubtleDark = Color(0xFF2B332E)

// Backward-compatibility aliases
val ForestDarkBg = BackgroundLight
val ForestSurface = SurfaceLight
val ForestSurfaceVariant = SurfaceVariantLight
val ForestCard = CardBackgroundLight
val ForestGlass = SurfaceLight
val GlassBorder = BorderSubtleLight
val GlassBorderSubtle = BorderSubtleLight
val TextPrimary = TextPrimaryLight
val TextSecondary = TextSecondaryLight
val TextMuted = TextMutedLight
val FriTeltningGreen = Color(0x332E6B3F)
val CompletedRouteColor = Color(0xFF9CA3AF)
val ActiveRouteColor = ForestGreenPrimary
val NearestGuideColor = WarningAmber
