package com.example.voicetomidi.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.voicetomidi.audio.AudioFileDecoder
import com.example.voicetomidi.audio.DemoScenario
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.Scale
import com.example.voicetomidi.notes.ScaleConstraint
import com.example.voicetomidi.notes.frenchNoteName
import com.example.voicetomidi.ui.theme.EmoButton
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import com.example.voicetomidi.ui.theme.SettingsDivider
import com.example.voicetomidi.ui.theme.SettingsSection
import com.example.voicetomidi.ui.theme.SettingsSwitchRow
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.IOException
import java.util.Locale

/**
 * Écran unique de l'appli, de haut en bas :
 * titre + réglages, grand texte d'état, courbe de la voix, résultat de la
 * prise (après l'arrêt), puis les commandes à portée de pouce : le pavé
 * "maintiens pendant la note" et le bouton d'enregistrement.
 */
@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    viewModel.diagnosticsDir = context.getExternalFilesDir("prises")
    viewModel.previewDir = context.cacheDir
    var showSettings by remember { mutableStateOf(false) }
    var showBpmDialog by remember { mutableStateOf(false) }
    // Note en cours de correction (roue des notes ouverte), et la hauteur visée par la roue.
    var editingNoteIndex by remember { mutableStateOf<Int?>(null) }
    var editingPitch by remember { mutableStateOf<Int?>(null) }
    val editNote = { index: Int ->
        viewModel.stopPreview()
        editingNoteIndex = index
        editingPitch = uiState.completedNotes.getOrNull(index)?.midiNote
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Android ne réaffiche plus la fenêtre de permission après deux refus : le
    // bouton doit alors renvoyer vers les réglages de l'appli.
    var permissionBlocked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        val activity = context.findActivity()
        permissionBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    }
    val askForMicrophone = {
        if (permissionBlocked) context.openAppSettings() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Première ouverture : on demande le micro tout de suite, sans attendre un appui.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Coupe la capture si l'app quitte le premier plan (mise en veille, appel entrant,
    // etc.) — sinon le micro continuerait de tourner en arrière-plan silencieusement.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.stopRecording()
                viewModel.stopPreview()
            }
            // Retour des réglages : la permission a peut-être été accordée entre-temps.
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasPermission) permissionBlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importAudio { AudioFileDecoder.decode(context, uri) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(viewModel.buildMidiFile())
            }
            Toast.makeText(context, "Fichier MIDI exporté", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(context, "Échec de l'export : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val idle = !uiState.isRecording && !uiState.isRefining
    val hasResult = idle && uiState.completedNotes.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "VoiceToMidi",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showSettings = true }) {
                Text("Réglages", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasPermission) {
                MicrophoneCard(blocked = permissionBlocked, onAllow = askForMicrophone)
            }

            StatusHeader(uiState)

            PitchTrail(
                points = uiState.pitchTrail,
                takeTrail = uiState.takeTrail,
                notes = uiState.completedNotes,
                playbackPositionMs = uiState.playbackPositionMs,
                cursorMs = uiState.cursorMs,
                selectedNoteIndex = editingNoteIndex,
                previewPitch = editingPitch,
                onNoteTap = editNote,
                onSeek = viewModel::seekTo,
                onScrub = viewModel::scrubTo,
                onScrubEnd = viewModel::endScrub,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(MaterialTheme.shapes.large)
            )

            if (hasResult && uiState.takeTrail != null) {
                Text(
                    "Touche une note pour la changer · glisse pour choisir où écouter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            uiState.errorMessage?.let { message -> ErrorCard(message) }

            AnimatedVisibility(visible = hasResult, enter = fadeIn(), exit = fadeOut()) {
                ResultCard(
                    notes = uiState.completedNotes,
                    isPlaying = uiState.playbackPositionMs != null,
                    onExport = {
                        viewModel.stopPreview()
                        showBpmDialog = true
                    },
                    onTogglePreview = viewModel::togglePreview,
                    onNoteClick = editNote,
                    onClear = viewModel::clearNotes
                )
            }
        }

        HoldToSingButton(
            isHolding = uiState.isHolding,
            enabled = !uiState.isRefining,
            onPressStart = {
                if (hasPermission || uiState.isRecording) {
                    viewModel.onHoldStart()
                } else {
                    askForMicrophone()
                }
            },
            onPressEnd = viewModel::onHoldEnd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(104.dp)
        )

        BottomControls(
            isRecording = uiState.isRecording,
            idle = idle,
            enabled = !uiState.isRefining,
            onRecord = {
                when {
                    uiState.isRecording -> viewModel.stopRecording()
                    hasPermission -> viewModel.startRecording()
                    else -> askForMicrophone()
                }
            },
            onImport = { importLauncher.launch(arrayOf("audio/*")) },
            onDemo = viewModel::startDemo
        )
    }

    editingNoteIndex?.let { index ->
        val note = uiState.completedNotes.getOrNull(index)
        if (note == null) {
            editingNoteIndex = null
            editingPitch = null
        } else {
            NotePickerSheet(
                position = index + 1,
                currentPitch = note.midiNote,
                onCentered = { pitch ->
                    editingPitch = pitch
                    viewModel.auditionNote(pitch)
                },
                onConfirm = { pitch ->
                    viewModel.setNotePitch(index, pitch)
                    editingNoteIndex = null
                    editingPitch = null
                },
                onDelete = {
                    viewModel.deleteNote(index)
                    editingNoteIndex = null
                    editingPitch = null
                },
                onDismiss = {
                    editingNoteIndex = null
                    editingPitch = null
                }
            )
        }
    }

    if (showSettings) {
        SettingsSheet(uiState = uiState, viewModel = viewModel, onDismiss = { showSettings = false })
    }

    if (showBpmDialog) {
        ExportBpmDialog(
            initialBpm = uiState.projectBpm ?: uiState.exportBpm,
            onConfirm = { bpm ->
                showBpmDialog = false
                viewModel.setExportBpm(bpm)
                exportLauncher.launch("voicetomidi_${System.currentTimeMillis()}.mid")
            },
            onDismiss = { showBpmDialog = false }
        )
    }
}

/** Grand texte d'état + une phrase qui dit quoi faire maintenant. */
@Composable
private fun StatusHeader(uiState: RecordingUiState) {
    val notes = uiState.completedNotes.size
    val title = when {
        uiState.isRefining -> "Analyse…"
        uiState.countInBeat != null -> "${uiState.countInBeat}"
        uiState.isRecording && uiState.currentNoteName != null -> uiState.currentNoteName!!
        uiState.isRecording -> "Chante"
        notes == 1 -> "1 note"
        notes > 0 -> "$notes notes"
        else -> "Prêt"
    }
    val subtitle = when {
        uiState.isRefining -> "Je cherche tes notes, quelques secondes."
        uiState.countInBeat != null -> "Écoute le décompte, chante après le dernier clic."
        uiState.isRecording && uiState.heldNoteCount == 1 -> "1 note marquée · touche ■ pour arrêter"
        uiState.isRecording && uiState.heldNoteCount > 0 -> "${uiState.heldNoteCount} notes marquées · touche ■ pour arrêter"
        uiState.isRecording -> "Chante librement, ou maintiens le pavé pendant chaque note."
        notes > 0 && uiState.detectedKeyName != null -> "Tonalité : ${uiState.detectedKeyName}"
        notes > 0 -> "Ta prise est prête."
        else -> "Touche le bouton rouge et chante ta mélodie."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            color = if (uiState.isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Ce qu'on peut faire de la prise : l'exporter (action principale), l'écouter
 * au piano avant, voir les notes, recommencer.
 */
@Composable
private fun ResultCard(
    notes: List<NoteEvent>,
    isPlaying: Boolean,
    onExport: () -> Unit,
    onTogglePreview: () -> Unit,
    onNoteClick: (Int) -> Unit,
    onClear: () -> Unit
) {
    var showNotes by remember { mutableStateOf(false) }
    EmoCard {
        EmoButton(text = "Exporter en MIDI", onClick = onExport, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmoOutlinedButton(
                text = if (isPlaying) "❚❚  Pause" else "▶  Écouter",
                onClick = onTogglePreview,
                modifier = Modifier.weight(1f)
            )
            EmoOutlinedButton(text = "Effacer", onClick = onClear, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = { showNotes = !showNotes }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(
                if (showNotes) "Masquer les notes" else "Voir les notes (${notes.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showNotes) {
            Column {
                notes.forEachIndexed { index, note ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNoteClick(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            frenchNoteName(note.midiNote),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            String.format(Locale.FRENCH, "%.2f s", note.durationMs / 1000.0),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Le micro n'est pas autorisé : on explique pourquoi et on propose de le faire. */
@Composable
private fun MicrophoneCard(blocked: Boolean, onAllow: () -> Unit) {
    EmoCard {
        Text("Micro désactivé", style = MaterialTheme.typography.titleMedium)
        Text(
            if (blocked) {
                "Le micro est bloqué pour cette appli. Dans les réglages : Autorisations → Micro → Autoriser."
            } else {
                "Autorise le micro pour enregistrer ta voix. Sans micro, tu peux importer un audio ou essayer la démo."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EmoButton(
            text = if (blocked) "Ouvrir les réglages" else "Autoriser le micro",
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(16.dp)
    )
}

/** Bas de l'écran, façon appareil photo : Importer · bouton d'enregistrement · Démo. */
@Composable
private fun BottomControls(
    isRecording: Boolean,
    idle: Boolean,
    enabled: Boolean,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onDemo: (DemoScenario) -> Unit
) {
    var showDemoMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SideAction("Importer", enabled = idle, onClick = onImport)
        }
        RecordButton(isRecording = isRecording, enabled = enabled, onClick = onRecord)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SideAction("Démo", enabled = idle, onClick = { showDemoMenu = true })
            DropdownMenu(expanded = showDemoMenu, onDismissRequest = { showDemoMenu = false }) {
                DemoScenario.entries.forEach { scenario ->
                    DropdownMenuItem(
                        text = { Text(scenario.displayName) },
                        onClick = {
                            showDemoMenu = false
                            onDemo(scenario)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SideAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * Réglages facultatifs, dans un panneau qui monte du bas : tout marche sans y
 * toucher. Le tempo et la gamme sont ceux du projet FL Studio, pour qui les connaît.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(uiState: RecordingUiState, viewModel: RecordingViewModel, onDismiss: () -> Unit) {
    val idle = !uiState.isRecording && !uiState.isRefining
    var bpmText by remember { mutableStateOf(uiState.projectBpm?.toString() ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Réglages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("OK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsSection(
                title = "Tempo",
                footer = if (uiState.projectBpm != null) {
                    "$COUNT_IN_BEATS clics de décompte avant de chanter (mieux avec un casque)."
                } else {
                    "Donne le tempo de ton projet pour avoir un décompte et caler les notes sur les temps."
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tempo du projet", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { new ->
                            bpmText = new.filter { it.isDigit() }.take(3)
                            viewModel.setProjectBpm(bpmText.toIntOrNull()?.takeIf { it in MIN_EXPORT_BPM..MAX_EXPORT_BPM })
                        },
                        placeholder = { Text("BPM") },
                        singleLine = true,
                        enabled = idle,
                        isError = bpmText.isNotEmpty() && uiState.projectBpm == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.width(120.dp)
                    )
                }
                if (uiState.projectBpm != null) {
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Métronome pendant la prise",
                        subtitle = null,
                        checked = uiState.isMetronomeEnabled,
                        enabled = idle,
                        onToggle = viewModel::toggleMetronome
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = "Caler les notes sur le tempo",
                        subtitle = "Quantification à la double-croche",
                        checked = uiState.isQuantizeEnabled,
                        enabled = true,
                        onToggle = viewModel::toggleQuantize
                    )
                }
            }

            SettingsSection(title = "Justesse") {
                ScalePickerRow(selected = uiState.projectScale, enabled = idle, onSelect = viewModel::setProjectScale)
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Rendre plus juste",
                    subtitle = "Si tu chantes entre deux notes",
                    checked = uiState.isPitchCorrectionEnabled,
                    enabled = idle,
                    onToggle = viewModel::togglePitchCorrection
                )
            }

            SettingsSection(title = "Export", footer = "Tout est facultatif : l'appli marche sans toucher à ces réglages.") {
                SettingsSwitchRow(
                    title = "Garder le vibrato",
                    subtitle = "Ajoute du pitch bend au fichier MIDI",
                    checked = uiState.isPitchBendEnabled,
                    enabled = true,
                    onToggle = viewModel::togglePitchBend
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Choix d'une note à la roue, façon sélecteur d'heure : chaque note qui passe
 * au centre est jouée au piano (et redessinée dans le graphique), pour
 * reconnaître à l'oreille celle qu'on avait en tête.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotePickerSheet(
    position: Int,
    currentPitch: Int,
    onCentered: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // Aigu en haut, grave en bas, comme dans le graphique.
    val pitches = remember(currentPitch) { (currentPitch + PICKER_RANGE downTo currentPitch - PICKER_RANGE).toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = PICKER_RANGE)
    val itemHeight = 48.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    // Avec 2 lignes de marge en haut, la note au centre est la 1re visible (arrondie à la plus proche).
    val centeredIndex by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex + if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0)
                .coerceIn(pitches.indices)
        }
    }
    val scope = rememberCoroutineScope()
    val currentOnCentered by rememberUpdatedState(onCentered)
    LaunchedEffect(pitches) {
        snapshotFlow { centeredIndex }.distinctUntilChanged().collect { currentOnCentered(pitches[it]) }
    }

    // Panneau compact et voile léger : le graphique reste visible au-dessus, la note y bouge en direct.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = Color.Black.copy(alpha = 0.2f)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Note $position", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Fais défiler : chaque note est jouée au piano.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight * PICKER_VISIBLE_ROWS)
            ) {
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
                    contentPadding = PaddingValues(vertical = itemHeight * (PICKER_VISIBLE_ROWS / 2)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(pitches) { index, pitch ->
                        val distance = abs(index - centeredIndex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .clickable {
                                    if (index == centeredIndex) currentOnCentered(pitch)
                                    else scope.launch { listState.animateScrollToItem(index) }
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                frenchNoteName(pitch),
                                style = if (distance == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                color = when (distance) {
                                    0 -> MaterialTheme.colorScheme.onSurface
                                    1 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                            if (pitch == currentPitch) {
                                Text(
                                    "  actuelle",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmoOutlinedButton(text = "Supprimer", onClick = onDelete, modifier = Modifier.weight(1f))
                EmoButton(
                    text = "Valider ${frenchNoteName(pitches[centeredIndex])}",
                    onClick = { onConfirm(pitches[centeredIndex]) },
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    }
}

/** La roue propose une octave au-dessus et une au-dessous de la note actuelle. */
private const val PICKER_RANGE = 12
private const val PICKER_VISIBLE_ROWS = 3

private val TONIC_NAMES = listOf("Do", "Do#", "Ré", "Mi♭", "Mi", "Fa", "Fa#", "Sol", "La♭", "La", "Si♭", "Si")

private fun ScaleConstraint.frenchName() =
    "${TONIC_NAMES[rootPitchClass]} ${if (scale == Scale.NATURAL_MINOR) "mineur" else "majeur"}"

/** "Gamme du projet" : Automatique, ou l'une des 24 gammes majeures / mineures. */
@Composable
private fun ScalePickerRow(selected: ScaleConstraint?, enabled: Boolean, onSelect: (ScaleConstraint?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(Scale.MAJOR, Scale.NATURAL_MINOR).flatMap { scale -> (0..11).map { ScaleConstraint(scale, it) } }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Gamme du projet",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${selected?.frenchName() ?: "Automatique"}  ›",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Automatique") }, onClick = { expanded = false; onSelect(null) })
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.frenchName()) }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

/** Demande le tempo du projet FL Studio avant l'export, pour que le rythme tombe juste à l'import. */
@Composable
private fun ExportBpmDialog(initialBpm: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialBpm.toString()) }
    val bpm = text.toIntOrNull()?.takeIf { it in MIN_EXPORT_BPM..MAX_EXPORT_BPM }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Tempo de ton projet", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Mets le même BPM que ton projet FL Studio : les notes tomberont au bon endroit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(3) },
                    label = { Text("BPM") },
                    singleLine = true,
                    isError = bpm == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { bpm?.let(onConfirm) }, enabled = bpm != null) {
                Text("Exporter", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Ouvre la page de l'appli dans les réglages Android (pour autoriser le micro à la main). */
private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
