package com.proedit.nle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ProEditDarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = BackgroundPrimary,
    secondary = AccentSecondary,
    onSecondary = TextPrimary,
    tertiary = SemanticGreen,
    error = SemanticRed,
    background = BackgroundPrimary,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = TrackBackground,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle
)

@Composable
fun ProEditNleTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ProEditDarkColorScheme,
        typography = ProEditTypography,
        content = content
    )
}
