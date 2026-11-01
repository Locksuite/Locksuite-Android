package com.ejemplo.locksuite.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 (M3) Dark Tonal Palette & Design Tokens para LockSuite MDM.
 *
 * Comparte la misma escala tonal M3 que el Panel Web (`styles.css` / `panel2.css`),
 * optimizada para pantallas chicas (CAT S22 Flip, Qin F21 Pro ~320dp) y grandes.
 */
object LockSuiteTokens {
    // M3 Surface Hierarchy (Dark Mode)
    val SurfaceDim = Color(0xFF0A0E14)
    val Surface = Color(0xFF0E141B)
    val SurfaceBright = Color(0xFF2B3543)
    val SurfaceContainerLowest = Color(0xFF080C11)
    val SurfaceContainerLow = Color(0xFF161D26)
    val SurfaceContainer = Color(0xFF1A222C)
    val SurfaceContainerHigh = Color(0xFF242D3A)
    val SurfaceContainerHighest = Color(0xFF2F3948)

    // Content & Outlines
    val OnSurface = Color(0xFFE2E8F0)
    val OnSurfaceVariant = Color(0xFF94A3B8)
    val Outline = Color(0xFF64748B)
    val OutlineVariant = Color(0xFF2A3647)

    // Primary (Warm Gold M3 Accent)
    val Primary = Color(0xFFF5C438)
    val OnPrimary = Color(0xFF261900)
    val PrimaryContainer = Color(0xFF584400)
    val OnPrimaryContainer = Color(0xFFFFE08B)

    // Secondary (Slate Blue Tonal)
    val Secondary = Color(0xFFBCC7DC)
    val OnSecondary = Color(0xFF263141)
    val SecondaryContainer = Color(0xFF283548)
    val OnSecondaryContainer = Color(0xFFD8E3F8)

    // Tertiary (Sky Cyan)
    val Tertiary = Color(0xFF67D4FF)
    val OnTertiary = Color(0xFF003546)
    val TertiaryContainer = Color(0xFF004D64)
    val OnTertiaryContainer = Color(0xFFBDE9FF)

    // Semantic Status
    val Error = Color(0xFFF87171)
    val OnError = Color(0xFF450A0A)
    val ErrorContainer = Color(0xFF4C141B)
    val OnErrorContainer = Color(0xFFFECACA)

    val Success = Color(0xFF34D399)
    val OnSuccess = Color(0xFF052E16)
    val SuccessContainer = Color(0xFF0E3F2A)
    val Warning = Color(0xFFFBBF24)
}

private val LockSuiteM3ColorScheme: ColorScheme = darkColorScheme(
    primary = LockSuiteTokens.Primary,
    onPrimary = LockSuiteTokens.OnPrimary,
    primaryContainer = LockSuiteTokens.PrimaryContainer,
    onPrimaryContainer = LockSuiteTokens.OnPrimaryContainer,
    secondary = LockSuiteTokens.Secondary,
    onSecondary = LockSuiteTokens.OnSecondary,
    secondaryContainer = LockSuiteTokens.SecondaryContainer,
    onSecondaryContainer = LockSuiteTokens.OnSecondaryContainer,
    tertiary = LockSuiteTokens.Tertiary,
    onTertiary = LockSuiteTokens.OnTertiary,
    tertiaryContainer = LockSuiteTokens.TertiaryContainer,
    onTertiaryContainer = LockSuiteTokens.OnTertiaryContainer,
    error = LockSuiteTokens.Error,
    onError = LockSuiteTokens.OnError,
    errorContainer = LockSuiteTokens.ErrorContainer,
    onErrorContainer = LockSuiteTokens.OnErrorContainer,
    background = LockSuiteTokens.Surface,
    onBackground = LockSuiteTokens.OnSurface,
    surface = LockSuiteTokens.Surface,
    onSurface = LockSuiteTokens.OnSurface,
    surfaceVariant = LockSuiteTokens.SurfaceContainerHigh,
    onSurfaceVariant = LockSuiteTokens.OnSurfaceVariant,
    surfaceContainerLowest = LockSuiteTokens.SurfaceContainerLowest,
    surfaceContainerLow = LockSuiteTokens.SurfaceContainerLow,
    surfaceContainer = LockSuiteTokens.SurfaceContainer,
    surfaceContainerHigh = LockSuiteTokens.SurfaceContainerHigh,
    surfaceContainerHighest = LockSuiteTokens.SurfaceContainerHighest,
    outline = LockSuiteTokens.Outline,
    outlineVariant = LockSuiteTokens.OutlineVariant
)

private val LockSuiteM3Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun isCompactScreen(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp < 360 || cfg.screenHeightDp < 600
}

@Composable
fun lockSuiteSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = LockSuiteTokens.OnPrimary,
    checkedTrackColor = LockSuiteTokens.Primary,
    checkedBorderColor = LockSuiteTokens.Primary,
    uncheckedThumbColor = LockSuiteTokens.Outline,
    uncheckedTrackColor = LockSuiteTokens.SurfaceContainerHighest,
    uncheckedBorderColor = LockSuiteTokens.Outline
)

@Composable
fun LockSuiteM3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LockSuiteM3ColorScheme,
        shapes = LockSuiteM3Shapes,
        content = content
    )
}
