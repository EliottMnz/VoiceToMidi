package com.example.voicetomidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.notes.ChordColor
import com.example.voicetomidi.notes.MAX_CHORD_SIZE
import com.example.voicetomidi.ui.theme.EmoButton
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Mode « accords » du mode Mélodie : on touche les notes à harmoniser dans le
 * graphique ([selectedCount] choisies), on règle à la molette le nombre de
 * notes de l'accord ([size]), puis on l'applique. Chaque cran de la molette
 * fait entendre l'accord ([onSizeChange]). « Note seule » retire l'accord.
 * La couleur ([color]) : « Auto » suit la tonalité, « Majeur » ou « Mineur »
 * l'imposent ; chaque choix se fait entendre aussi ([onColorChange]).
 * On peut écouter le résultat sans quitter le mode, puis « Terminé ».
 */
@Composable
fun ChordCard(
    selectedCount: Int,
    size: Int,
    onSizeChange: (Int) -> Unit,
    color: ChordColor,
    onColorChange: (ChordColor) -> Unit,
    onApply: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    isPlaying: Boolean,
    onTogglePreview: () -> Unit,
    onDone: () -> Unit
) {
    EmoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Accords", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (selectedCount) {
                        0 -> "Touche les notes à mettre en accord."
                        1 -> "1 note choisie"
                        else -> "$selectedCount notes choisies"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = if (selectedCount > 0) onClearSelection else onSelectAll) {
                Text(
                    if (selectedCount > 0) "Tout désélectionner" else "Tout choisir",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        ChordColorPicker(selected = color, onSelected = onColorChange, enabled = size > 1)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CountWheel(
                values = (1..MAX_CHORD_SIZE).toList(),
                selected = size,
                label = { if (it == 1) "Note seule" else "$it notes" },
                onSelected = onSizeChange,
                modifier = Modifier.width(150.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        size == 1 -> "Retire l'accord des notes choisies."
                        color == ChordColor.AUTO -> "Chaque note choisie devient un accord de $size notes, dans la tonalité de ta mélodie."
                        color == ChordColor.MAJOR -> "Chaque note choisie devient un accord majeur de $size notes : plus joyeux."
                        else -> "Chaque note choisie devient un accord mineur de $size notes : plus doux, plus triste."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                EmoButton(
                    text = if (size == 1) "Retirer l'accord" else "Faire l'accord",
                    onClick = onApply,
                    enabled = selectedCount > 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmoOutlinedButton(
                text = if (isPlaying) "❚❚  Pause" else "▶  Écouter",
                onClick = onTogglePreview,
                modifier = Modifier.weight(1f)
            )
            EmoOutlinedButton(text = "Terminé", onClick = onDone, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Choix de la couleur de l'accord, en trois boutons collés (façon sélecteur
 * iOS) : celui choisi est rouge plein. Grisé quand il n'y a pas d'accord.
 */
@Composable
private fun ChordColorPicker(selected: ChordColor, onSelected: (ChordColor) -> Unit, enabled: Boolean) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (color in ChordColor.entries) {
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected && enabled) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled) {
                        if (!isSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelected(color)
                        }
                    }
                    .semantics { contentDescription = "Accord ${color.label}" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    color.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

/**
 * Petite molette verticale, comme un sélecteur d'heure : la valeur au centre
 * est la valeur choisie, avec une petite vibration à chaque cran. Toucher une
 * valeur y va directement.
 */
@Composable
fun CountWheel(
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = 44.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(selected).coerceAtLeast(0))
    val centeredIndex by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex + if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0)
                .coerceIn(values.indices)
        }
    }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(values) {
        // drop(1) : la valeur de départ n'est pas un choix de l'utilisateur (pas de son ni de vibration).
        snapshotFlow { centeredIndex }.distinctUntilChanged().drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            currentOnSelected(values[it])
        }
    }

    Box(modifier.height(itemHeight * WHEEL_VISIBLE_ROWS)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            contentPadding = PaddingValues(vertical = itemHeight * (WHEEL_VISIBLE_ROWS / 2)),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(values) { index, value ->
                val distance = abs(index - centeredIndex)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable { scope.launch { listState.animateScrollToItem(index) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(value),
                        style = if (distance == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color = when (distance) {
                            0 -> MaterialTheme.colorScheme.onSurface
                            1 -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
        }
    }
}

/** La molette montre la valeur choisie et une de chaque côté. */
private const val WHEEL_VISIBLE_ROWS = 3
