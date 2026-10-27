package com.example.voicetomidi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.project.Project
import com.example.voicetomidi.project.ProjectTrack
import com.example.voicetomidi.project.TrackKind
import com.example.voicetomidi.ui.theme.EmoButton
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import com.example.voicetomidi.ui.theme.ScreenHeader
import java.util.Locale

/** Composition : la liste des projets. */
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onBack: () -> Unit,
    onOpenProject: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var creating by remember { mutableStateOf(false) }

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
            if (uiState.projects.isEmpty()) {
                EmoCard {
                    Text("Aucun projet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Un projet rassemble des mélodies (et bientôt des drums) qui jouent ensemble. " +
                            "Crée-en un ici, ou envoie une mélodie depuis le mode Mélodie.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    EmoButton(text = "Nouveau projet", onClick = { creating = true }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                uiState.projects.forEach { project ->
                    ProjectCard(project = project, onClick = { onOpenProject(project.id) })
                }
                EmoOutlinedButton(text = "Nouveau projet", onClick = { creating = true }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (creating) {
        ProjectNameDialog(
            title = "Nouveau projet",
            initialName = viewModel.suggestedProjectName(),
            confirmLabel = "Créer",
            onConfirm = { name ->
                creating = false
                onOpenProject(viewModel.createProject(name))
            },
            onDismiss = { creating = false }
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    trackSummary(project),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Aperçu : les pistes empilées sur une même ligne de temps.
        if (project.tracks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                project.tracks.take(3).forEach { track ->
                    NoteStrip(
                        notes = track.notes,
                        totalMs = project.durationMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .alpha(if (track.muted) 0.35f else 1f)
                    )
                }
            }
        }
    }
}

private fun trackSummary(project: Project): String {
    val count = project.tracks.size
    val tracks = when (count) {
        0 -> "Vide"
        1 -> "1 piste"
        else -> "$count pistes"
    }
    return if (count == 0) tracks else tracks + " · " + formatDuration(project.durationMs)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/**
 * Un projet : un bouton de lecture pour tout jouer ensemble, et les pistes
 * alignées sur la même ligne de temps, chacune avec son bouton muet.
 */
@Composable
fun ProjectScreen(
    projectId: Long,
    viewModel: ProjectsViewModel,
    onBack: () -> Unit,
    onAddMelody: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val project = uiState.projects.firstOrNull { it.id == projectId }
    // Arriver sur un projet repart du début ; le quitter coupe le son.
    LaunchedEffect(projectId) { viewModel.resetPosition() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (project == null) {
        // Projet supprimé : retour à la liste.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val isPlaying = uiState.playingProjectId == project.id
    val position = uiState.positionMs.coerceAtMost(project.durationMs)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(title = project.name, onBack = onBack, backLabel = "Projets") {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("•••", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menuOpen = false; renaming = true })
                    DropdownMenuItem(text = { Text("Supprimer le projet") }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (project.tracks.isEmpty()) {
                EmoCard {
                    Text("Projet vide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ajoute une mélodie : chante-la, elle arrivera ici. Toutes les pistes d'un projet jouent ensemble.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Transport(
                    isPlaying = isPlaying,
                    positionMs = position,
                    durationMs = project.durationMs,
                    hasAudibleTrack = project.tracks.any { !it.muted },
                    onTogglePlay = { viewModel.togglePlay(project.id) }
                )
                project.tracks.forEach { track ->
                    TrackRow(
                        track = track,
                        totalMs = project.durationMs,
                        positionMs = position.takeIf { isPlaying || it > 0L },
                        onToggleMute = { viewModel.toggleMute(project.id, track.id) },
                        onSeek = { viewModel.seek(project.id, it) },
                        onRemove = { viewModel.removeTrack(project.id, track.id) }
                    )
                }
            }
            EmoButton(text = "Ajouter une mélodie", onClick = onAddMelody, modifier = Modifier.fillMaxWidth())
            EmoOutlinedButton(text = "Ajouter des drums · bientôt", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        }
    }

    if (renaming) {
        ProjectNameDialog(
            title = "Renommer le projet",
            initialName = project.name,
            confirmLabel = "Renommer",
            onConfirm = { name ->
                renaming = false
                viewModel.renameProject(project.id, name)
            },
            onDismiss = { renaming = false }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Supprimer « ${project.name} » ?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Le projet et ses ${project.tracks.size} pistes seront effacés. Les fichiers MIDI déjà exportés ne sont pas touchés.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteProject(project.id)
                }) { Text("Supprimer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Annuler", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** Lecture de tout le projet : gros bouton rond, position et durée. */
@Composable
private fun Transport(isPlaying: Boolean, positionMs: Long, durationMs: Long, hasAudibleTrack: Boolean, onTogglePlay: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (hasAudibleTrack) accent else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = hasAudibleTrack, role = Role.Button, onClick = onTogglePlay)
                .semantics { contentDescription = if (isPlaying) "Pause" else "Tout écouter" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                if (isPlaying) {
                    val barWidth = size.width * 0.3f
                    drawRoundRect(onAccent, Offset(size.width * 0.12f, 0f), Size(barWidth, size.height), CornerRadius(2.dp.toPx()))
                    drawRoundRect(onAccent, Offset(size.width * 0.58f, 0f), Size(barWidth, size.height), CornerRadius(2.dp.toPx()))
                } else {
                    val triangle = Path().apply {
                        moveTo(size.width * 0.18f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.18f, size.height)
                        close()
                    }
                    drawPath(triangle, onAccent)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (hasAudibleTrack) "Toutes les pistes actives jouent ensemble" else "Toutes les pistes sont coupées",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Une piste : bouton muet, nom, notes sur la ligne de temps commune (toucher = écouter d'ici). */
@Composable
private fun TrackRow(
    track: ProjectTrack,
    totalMs: Long,
    positionMs: Long?,
    onToggleMute: () -> Unit,
    onSeek: (Long) -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MuteButton(muted = track.muted, onToggle = onToggleMute)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (track.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (track.muted) "coupée" else if (track.kind == TrackKind.MELODY) "${track.notes.size} notes" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NoteStrip(
                notes = track.notes,
                totalMs = totalMs,
                positionMs = positionMs,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (track.muted) 0.4f else 1f)
            )
        }
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text("•••", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Retirer du projet") }, onClick = { menuOpen = false; onRemove() })
            }
        }
    }
}

/** Haut-parleur : rouge quand la piste joue, barré et gris quand elle est coupée. */
@Composable
private fun MuteButton(muted: Boolean, onToggle: () -> Unit) {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics { contentDescription = if (muted) "Réactiver la piste" else "Couper la piste" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val speaker = Path().apply {
                moveTo(0f, h * 0.35f)
                lineTo(w * 0.25f, h * 0.35f)
                lineTo(w * 0.55f, h * 0.1f)
                lineTo(w * 0.55f, h * 0.9f)
                lineTo(w * 0.25f, h * 0.65f)
                lineTo(0f, h * 0.65f)
                close()
            }
            drawPath(speaker, color)
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            if (muted) {
                drawLine(color, Offset(w * 0.68f, h * 0.35f), Offset(w * 0.98f, h * 0.65f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.98f, h * 0.35f), Offset(w * 0.68f, h * 0.65f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            } else {
                drawArc(color, -45f, 90f, useCenter = false, topLeft = Offset(w * 0.35f, h * 0.25f), size = Size(w * 0.45f, h * 0.5f), style = stroke)
                drawArc(color, -50f, 100f, useCenter = false, topLeft = Offset(w * 0.3f, h * 0.05f), size = Size(w * 0.7f, h * 0.9f), style = stroke)
            }
        }
    }
}

/**
 * Notes d'une piste en petites barres, sur une ligne de temps de [totalMs]
 * (celle du projet : les pistes sont alignées entre elles). Avec [onSeek],
 * toucher la bande place la lecture à cet endroit.
 */
@Composable
private fun NoteStrip(
    notes: List<NoteEvent>,
    totalMs: Long,
    modifier: Modifier = Modifier,
    positionMs: Long? = null,
    onSeek: ((Long) -> Unit)? = null
) {
    val color = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.onSurface
    val currentOnSeek by rememberUpdatedState(onSeek)
    val gestures = if (onSeek == null) Modifier else Modifier.pointerInput(totalMs) {
        detectTapGestures { pos -> currentOnSeek?.invoke((pos.x / size.width * totalMs).toLong()) }
    }
    Canvas(modifier.then(gestures).padding(horizontal = 6.dp, vertical = 4.dp)) {
        val end = totalMs.coerceAtLeast(1L).toFloat()
        if (notes.isNotEmpty()) {
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
        positionMs?.let {
            val x = it / end * size.width
            drawLine(playhead, Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Nom d'un projet à saisir (création ou renommage), pré-rempli. */
@Composable
fun ProjectNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Nom") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * « Envoyer dans… » : choisir un projet existant, ou en créer un. La
 * mélodie y est ajoutée comme nouvelle piste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToProjectSheet(
    projects: List<Project>,
    suggestedName: String,
    onSendTo: (Long) -> Unit,
    onCreateAndSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf<String?>(if (projects.isEmpty()) suggestedName else null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Envoyer dans…", style = MaterialTheme.typography.titleLarge)
            if (projects.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    projects.forEachIndexed { index, project ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSendTo(project.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(project.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                trackSummary(project),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            val editing = newName
            if (editing == null) {
                EmoOutlinedButton(text = "+  Nouveau projet", onClick = { newName = suggestedName }, modifier = Modifier.fillMaxWidth())
            } else {
                EmoCard {
                    Text("Nouveau projet", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = editing,
                        onValueChange = { newName = it.take(40) },
                        label = { Text("Nom") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                    EmoButton(
                        text = "Créer et envoyer",
                        onClick = { onCreateAndSend(editing) },
                        enabled = editing.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
