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
    outline = EmoAccentDeep
)

// Coins carrés partout (look flyer/affiche, pas de courbes).
private val EmoShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

/** Identité visuelle unique de l'app : noir, rouge sang, anguleux. */
@Composable
fun VoiceToMidiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EmoColorScheme,
        typography = Typography,
        shapes = EmoShapes,
        content = content
    )
}
