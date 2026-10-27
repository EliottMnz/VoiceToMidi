package com.example.voicetomidi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EmoColorScheme = darkColorScheme(
    primary = EmoAccent,
    onPrimary = EmoOnAccent,
    secondary = EmoAccentDeep,
    onSecondary = EmoOnAccent,
    tertiary = EmoAccent,
    onTertiary = EmoOnAccent,
    background = EmoBackground,
    onBackground = EmoText,
    surface = EmoSurface,
    onSurface = EmoText,
    surfaceVariant = EmoSurfaceVariant,
    onSurfaceVariant = EmoTextMuted,
    surfaceContainerLow = EmoSurface,
    surfaceContainer = EmoSurface,
    surfaceContainerHigh = EmoSurfaceVariant,
    outline = EmoSeparator,
    outlineVariant = EmoSeparator
)

// Coins arrondis et doux : les cartes et boutons se lisent comme des blocs distincts.
private val EmoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Identité visuelle unique de l'app : noir, rouge sang, sobre. */
@Composable
fun VoiceToMidiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EmoColorScheme,
        typography = Typography,
        shapes = EmoShapes,
        content = content
    )
}
