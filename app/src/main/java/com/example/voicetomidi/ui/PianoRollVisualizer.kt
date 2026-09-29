package com.example.voicetomidi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.notes.midiNoteName

private const val LOW_NOTE = 33 // A1
private const val HIGH_NOTE = 96 // C7
private val BLACK_KEY_SEMITONES = setOf(1, 3, 6, 8, 10)

/**
 * Clavier de piano vertical (aigu en haut, grave en bas) : chaque touche a sa
 * propre ligne, avec le nom de la note, et une jauge horizontale qui se
 * remplit vers la droite selon [intensity] quand [activeMidiNote] correspond.
 */
@Composable
fun PianoRollVisualizer(
    activeMidiNote: Int?,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val notes = remember { (LOW_NOTE..HIGH_NOTE).toList().asReversed() }
    val listState = rememberLazyListState()

    LaunchedEffect(activeMidiNote) {
        val index = activeMidiNote?.let { notes.indexOf(it) } ?: return@LaunchedEffect
        if (index >= 0) {
            val centered = (index - 4).coerceAtLeast(0)
            listState.animateScrollToItem(centered)
        }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        items(notes, key = { it }) { note ->
            PianoKeyRow(
                note = note,
                isActive = note == activeMidiNote,
                intensity = if (note == activeMidiNote) intensity else 0f
            )
        }
    }
}

@Composable
private fun PianoKeyRow(note: Int, isActive: Boolean, intensity: Float) {
    val isBlackKey = note % 12 in BLACK_KEY_SEMITONES

    val restingKeyColor = if (isBlackKey) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val keyColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else restingKeyColor,
        animationSpec = tween(120),
        label = "keyColor"
    )
    val textColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val animatedIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        animationSpec = tween(100),
        label = "intensity"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight()
                .background(keyColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = midiNoteName(note),
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedIntensity)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
