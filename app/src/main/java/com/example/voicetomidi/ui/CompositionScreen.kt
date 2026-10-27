package com.example.voicetomidi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.ui.theme.EmoButton
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import com.example.voicetomidi.ui.theme.ScreenHeader
import java.util.Locale

/**
 * Mode Composition : les pistes envoyées depuis les autres modes. Pour
 * l'instant on peut les voir, les écouter et les retirer.
 */
@Composable
fun CompositionScreen(
    viewModel: CompositionViewModel,
    onBack: () -> Unit,
    onCreateMelody: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    // En quittant l'écran, l'écoute s'arrête.
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(title = "Composition", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.tracks.isEmpty()) {
                EmoCard {
                    Text("Ta composition est vide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Chante une mélodie, puis touche « Envoyer dans la composition » : elle apparaîtra ici.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    EmoButton(text = "Créer une mélodie", onClick = onCreateMelody, modifier = Modifier.fillMaxWidth())
                }
            } else {
                uiState.tracks.forEach { track ->
                    TrackCard(
                        track = track,
                        isPlaying = uiState.playingTrackId == track.id,
                        onTogglePlay = { viewModel.togglePlay(track) },
                        onRemove = { viewModel.removeTrack(track.id) }
                    )
                }
                EmoOutlinedButton(text = "Ajouter une mélodie", onClick = onCreateMelody, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Bientôt : ajouter des drums, caler les pistes ensemble et exporter le morceau entier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun TrackCard(track: CompositionTrack, isPlaying: Boolean, onTogglePlay: () -> Unit, onRemove: () -> Unit) {
    EmoCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${track.notes.size} notes · " + String.format(Locale.FRENCH, "%.1f s", track.durationMs / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NoteStrip(
            notes = track.notes,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmoOutlinedButton(
                text = if (isPlaying) "■  Arrêter" else "▶  Écouter",
                onClick = onTogglePlay,
                modifier = Modifier.weight(1f)
            )
            EmoOutlinedButton(text = "Retirer", onClick = onRemove, modifier = Modifier.weight(1f))
        }
    }
}

/** Aperçu d'une piste : ses notes en petites barres, comme un piano roll miniature. */
@Composable
private fun NoteStrip(notes: List<NoteEvent>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (notes.isEmpty()) return@Canvas
        val end = notes.maxOf { it.endTimeMs }.coerceAtLeast(1L).toFloat()
        val low = notes.minOf { it.midiNote }
        val rows = (notes.maxOf { it.midiNote } - low + 1).coerceAtLeast(6)
        val rowHeight = size.height / rows
        val barHeight = (rowHeight * 0.8f).coerceAtLeast(2.dp.toPx())
        for (note in notes) {
            val left = note.startTimeMs / end * size.width
            val width = ((note.endTimeMs - note.startTimeMs) / end * size.width - 1.dp.toPx()).coerceAtLeast(2.dp.toPx())
            val centerY = size.height - (note.midiNote - low + 0.5f) * rowHeight
            drawRoundRect(color, Offset(left, centerY - barHeight / 2), Size(width, barHeight), CornerRadius(barHeight / 2))
        }
    }
}
