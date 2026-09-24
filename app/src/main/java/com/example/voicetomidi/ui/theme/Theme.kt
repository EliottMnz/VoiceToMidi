package com.example.voicetomidi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val StudioDarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val StudioLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

private val VinylDarkColorScheme = darkColorScheme(
    primary = VinylPrimary80,
    secondary = VinylSecondary80,
    tertiary = VinylTertiary80,
    background = VinylBackgroundDark,
    surface = VinylSurfaceDark
)

private val VinylLightColorScheme = lightColorScheme(
    primary = VinylPrimary40,
    secondary = VinylSecondary40,
    tertiary = VinylTertiary40,
    background = VinylBackgroundLight,
    surface = VinylSurfaceLight
)

private val SynthwaveDarkColorScheme = darkColorScheme(
    primary = SynthPrimary80,
    secondary = SynthSecondary80,
    tertiary = SynthTertiary80,
    background = SynthBackgroundDark,
    surface = SynthSurfaceDark
)

private val SynthwaveLightColorScheme = lightColorScheme(
    primary = SynthPrimary40,
    secondary = SynthSecondary40,
    tertiary = SynthTertiary40,
    background = SynthBackgroundLight,
    surface = SynthSurfaceLight
)

@Composable
fun VoiceToMidiTheme(
    appTheme: AppTheme = AppTheme.Studio,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.Studio -> if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme
        AppTheme.Vinyl -> if (darkTheme) VinylDarkColorScheme else VinylLightColorScheme
        AppTheme.Synthwave -> if (darkTheme) SynthwaveDarkColorScheme else SynthwaveLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}