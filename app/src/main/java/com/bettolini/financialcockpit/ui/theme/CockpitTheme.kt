package com.bettolini.financialcockpit.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Extracted Swatches from Vector Gradient Color Set
val VioletTop = Color(0xFF8B4ABE)
val VioletBot = Color(0xFF38226A)

val PlumTop = Color(0xFFA85990)
val PlumBot = Color(0xFF491E49)

val OrchidTop = Color(0xFF8F17AC)
val OrchidBot = Color(0xFF460A52)

val GoldTop = Color(0xFFFEC552)
val GoldBot = Color(0xFFFF9D1E)

val AmethystTop = Color(0xFF7752A2)
val AmethystBot = Color(0xFF322553)

val AmberTop = Color(0xFFFAB301)
val AmberBot = Color(0xFFFF8601)

val IndigoTop = Color(0xFF5D4986)
val IndigoBot = Color(0xFF2F234B)

// Light & Dark Surface Tokens
val LightBackground = Color(0xFFEEF7FF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceMuted = Color(0xFFE3EFFB)
val LightBorder = Color(0xFFD5E4F4)
val LightTextPrimary = Color(0xFF19132E)
val LightTextSecondary = Color(0xFF625B7A)

val DarkBackground = Color(0xFF0D0A18)
val DarkSurface = Color(0xFF171229)
val DarkSurfaceMuted = Color(0xFF211A3A)
val DarkBorder = Color(0xFF2C234B)
val DarkTextPrimary = Color(0xFFF5F3FC)
val DarkTextSecondary = Color(0xFF9C93BA)

@Immutable
data class CockpitColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primaryAccent: Color,
    val amberAccent: Color,
    val plumAccent: Color,
    val positiveGreen: Color,
    val heroGradient: Brush,
    val amberGradient: Brush
)

val LightCockpitColors = CockpitColors(
    isDark = false,
    background = LightBackground,
    surface = LightSurface,
    surfaceMuted = LightSurfaceMuted,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    primaryAccent = Color(0xFF6D35A3),
    amberAccent = Color(0xFFE58200),
    plumAccent = Color(0xFF8F3F78),
    positiveGreen = Color(0xFF12945A),
    heroGradient = Brush.linearGradient(listOf(VioletTop, VioletBot)),
    amberGradient = Brush.horizontalGradient(listOf(AmberTop, AmberBot))
)

val DarkCockpitColors = CockpitColors(
    isDark = true,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceMuted = DarkSurfaceMuted,
    border = DarkBorder,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    primaryAccent = Color(0xFFB57EE8),
    amberAccent = Color(0xFFFFB429),
    plumAccent = Color(0xFFD47BB8),
    positiveGreen = Color(0xFF2EE58F),
    heroGradient = Brush.linearGradient(listOf(VioletTop, IndigoBot)),
    amberGradient = Brush.horizontalGradient(listOf(AmberTop, AmberBot))
)

val LocalCockpitColors = staticCompositionLocalOf { DarkCockpitColors }

val CockpitLightColorScheme = lightColorScheme(
    primary = Color(0xFF6D35A3),
    secondary = Color(0xFF8F3F78),
    tertiary = Color(0xFFE58200),
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary
)

val CockpitDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB57EE8),
    secondary = Color(0xFFD47BB8),
    tertiary = Color(0xFFFFB429),
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary
)

fun getVaultPalettePair(key: String): Pair<Color, Color> = when (key) {
    "SUSTENANCE_FLEET" -> VioletTop to VioletBot
    "FIXED_OVERHEAD" -> AmberTop to AmberBot
    "DISCRETIONARY" -> PlumTop to PlumBot
    "TRAVEL" -> OrchidTop to OrchidBot
    "WEALTH_SURPLUS" -> GoldTop to GoldBot
    "RENT" -> AmethystTop to AmethystBot
    else -> IndigoTop to IndigoBot
}

fun getVaultSolidColor(key: String, isDark: Boolean): Color = when (key) {
    "SUSTENANCE_FLEET" -> if (isDark) Color(0xFFA96EE2) else VioletTop
    "FIXED_OVERHEAD" -> if (isDark) Color(0xFFFFB429) else Color(0xFFE88900)
    "DISCRETIONARY" -> if (isDark) Color(0xFFD177B5) else PlumTop
    "TRAVEL" -> if (isDark) Color(0xFFCC5CE6) else OrchidTop
    "WEALTH_SURPLUS" -> if (isDark) GoldTop else Color(0xFFD98B00)
    "RENT" -> if (isDark) Color(0xFF9C78CC) else AmethystTop
    else -> IndigoTop
}

@Composable
fun ProvideCockpitColors(colors: CockpitColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCockpitColors provides colors, content = content)
}
