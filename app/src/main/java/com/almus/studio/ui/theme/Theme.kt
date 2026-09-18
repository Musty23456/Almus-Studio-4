package com.almus.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AlmusDarkColors = darkColorScheme(
    background = StudioBackground,
    surface = StudioSurface,
    surfaceVariant = StudioSurfaceVariant,
    primary = StudioAccent,
    secondary = StudioAccentVariant,
    error = StudioRecord,
    onBackground = StudioTextPrimary,
    onSurface = StudioTextPrimary,
    onPrimary = StudioBackground,
    onSecondary = StudioBackground
)

@Composable
fun AlmusStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Almus Studio is intentionally always dark, matching every mainstream
    // mobile DAW; darkTheme is accepted for API consistency but ignored.
    MaterialTheme(
        colorScheme = AlmusDarkColors,
        typography = AlmusTypography,
        content = content
    )
}
