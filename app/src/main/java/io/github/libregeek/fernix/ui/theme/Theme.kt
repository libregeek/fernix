// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import io.github.libregeek.fernix.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The palette (spec/architecture.md §9): Fern Green is the main colour (primary: play, selection, the
 * navigation pill), Burgundy the accent for what is personal (secondary: favourites, filter chips) and
 * Purple for what you can tap through to (tertiary: people and tag links, role pills). The neutrals are
 * tinted with the green so the surfaces belong to it. A fixed palette, not the wallpaper's dynamic colour.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6B34),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDE3AB),
    onPrimaryContainer = Color(0xFF0B2A06),
    secondary = Color(0xFF8E1B3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE79DB1),
    onSecondaryContainer = Color(0xFF3A0013),
    tertiary = Color(0xFF5B34A0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9B0F0),
    onTertiaryContainer = Color(0xFF1C0049),
    background = Color(0xFFF7FAF2),
    onBackground = Color(0xFF181D16),
    surface = Color(0xFFF7FAF2),
    onSurface = Color(0xFF181D16),
    surfaceVariant = Color(0xFFDDE5D6),
    onSurfaceVariant = Color(0xFF424940),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEAF2E4),
    surfaceContainer = Color(0xFFE1ECDA),
    surfaceContainerHigh = Color(0xFFD7E5CE),
    surfaceContainerHighest = Color(0xFFCCDDC2),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3D494),
    onPrimary = Color(0xFF113909),
    primaryContainer = Color(0xFF2A5121),
    onPrimaryContainer = Color(0xFFBEF0AE),
    secondary = Color(0xFFF2A9BA),
    onSecondary = Color(0xFF4C0A1F),
    secondaryContainer = Color(0xFF8A1F43),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFD4BEFF),
    onTertiary = Color(0xFF3A1B72),
    tertiaryContainer = Color(0xFF5F3FA3),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceContainerLow = Color(0xFF181C16),
    surfaceContainer = Color(0xFF1C201A),
    surfaceContainerHigh = Color(0xFF262B24),
    surfaceContainerHighest = Color(0xFF31362E),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
)

/** The system's condensed sans (Roboto Condensed on Android): narrower, so more fits a line, and no font file in the APK. */
private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
private val CondensedBold = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

private fun TextStyle.condensed(bold: Boolean = false) = copy(fontFamily = if (bold) CondensedBold else Condensed)

private val Base = Typography()

/** Material's scale, in the condensed face, with the titles bold and the line heights a little tighter. */
private val FernixTypography = Typography(
    displayLarge = Base.displayLarge.condensed(),
    displayMedium = Base.displayMedium.condensed(),
    displaySmall = Base.displaySmall.condensed(),
    headlineLarge = Base.headlineLarge.condensed(true),
    headlineMedium = Base.headlineMedium.condensed(true),
    headlineSmall = Base.headlineSmall.condensed(true),
    titleLarge = Base.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp).condensed(true),
    titleMedium = Base.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp).condensed(true),
    titleSmall = Base.titleSmall.copy(lineHeight = 18.sp).condensed(true),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.2.sp).condensed(),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp).condensed(),
    bodySmall = Base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp).condensed(),
    labelLarge = Base.labelLarge.copy(lineHeight = 18.sp).condensed(true),
    labelMedium = Base.labelMedium.copy(lineHeight = 16.sp).condensed(true),
    labelSmall = Base.labelSmall.copy(lineHeight = 14.sp).condensed(true),
)

private val FernixShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Whether the Fernix theme's soft gradient is behind the screens (only the Fernix theme has it; System and Dark are flat). */
val LocalFernixGradient = compositionLocalOf { false }

/** Screens draw on this when the gradient is on: the green wash at the top fading into the background. */
fun fernixGradient(colors: ColorScheme): Brush = Brush.verticalGradient(
    0f to colors.primaryContainer.copy(alpha = 0.95f),
    0.4f to colors.primaryContainer.copy(alpha = 0.4f),
    0.7f to colors.tertiaryContainer.copy(alpha = 0.5f),
    1f to colors.secondaryContainer.copy(alpha = 0.55f),
)

@Composable
fun FernixTheme(
    mode: ThemeMode,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when (mode) {
        ThemeMode.Fernix -> LightColors
        ThemeMode.Dark -> DarkColors
        // Stock Material: the wallpaper's colours on Android 12+, Material's baseline palette before that.
        ThemeMode.System -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            systemDark -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
    MaterialTheme(colorScheme = colors, typography = FernixTypography, shapes = FernixShapes) {
        CompositionLocalProvider(LocalFernixGradient provides (mode == ThemeMode.Fernix), content = content)
    }
}

/** The app's backdrop: the Fernix gradient in the Fernix theme, the plain background otherwise. */
@Composable
fun FernixBackground(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(if (LocalFernixGradient.current) fernixGradient(colors) else SolidColor(colors.background)),
    ) { content() }
}
