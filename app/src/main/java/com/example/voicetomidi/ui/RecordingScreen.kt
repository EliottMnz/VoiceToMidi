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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.voicetomidi.notes.frenchNoteName
import com.example.voicetomidi.ui.theme.EmoButton
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import java.io.IOException

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    viewModel.diagnosticsDir = context.getExternalFilesDir("prises")
    var showHistory by remember { mutableStateOf(false) }
    var showDemoMenu by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showBpmDialog by remember { mutableStateOf(false) }

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
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopRecording()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "VOICE TO MIDI",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (!hasPermission) {
                Text(
                    if (permissionBlocked) {
                        "Le micro est bloqué pour cette appli. Ouvre les réglages, puis Autorisations → Micro → Autoriser. (Tu peux aussi importer un audio ou lancer la démo.)"
                    } else {
                        "Micro non autorisé — autorise-le pour enregistrer ta voix, ou importe un audio / lance la démo."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPermission) {
                    EmoButton(
                        text = if (uiState.isRecording) "Arrêter" else "Enregistrer",
                        onClick = { if (uiState.isRecording) viewModel.stopRecording() else viewModel.startRecording() },
                        enabled = !uiState.isRefining
                    )
                } else {
                    EmoButton(
                        text = if (permissionBlocked) "Ouvrir les réglages" else "Autoriser le micro",
                        onClick = askForMicrophone
                    )
                }
                EmoOutlinedButton(
                    text = "Importer",
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    enabled = !uiState.isRecording && !uiState.isRefining
                )
                Box {
                    EmoOutlinedButton(
                        text = "Démo",
                        onClick = { showDemoMenu = true },
                        enabled = !uiState.isRecording && !uiState.isRefining
                    )
                    DropdownMenu(expanded = showDemoMenu, onDismissRequest = { showDemoMenu = false }) {
                        DemoScenario.entries.forEach { scenario ->
                            DropdownMenuItem(
                                text = { Text(scenario.displayName) },
                                onClick = {
                                    showDemoMenu = false
                                    viewModel.startDemo(scenario)
                                }
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmoOutlinedButton(
                    text = "Effacer",
                    onClick = viewModel::clearNotes,
                    enabled = uiState.completedNotes.isNotEmpty() && !uiState.isRefining
                )
                EmoOutlinedButton(
                    text = "Exporter",
                    onClick = { showBpmDialog = true },
                    enabled = uiState.completedNotes.isNotEmpty() && !uiState.isRecording && !uiState.isRefining
                )
            }

            Text(
                text = when {
                    uiState.isRefining -> "Analyse en cours…"
                    uiState.currentNoteName != null -> uiState.currentNoteName!!
                    uiState.isRecording -> "Chante !"
                    uiState.completedNotes.size == 1 -> "1 note"
                    uiState.completedNotes.isNotEmpty() -> "${uiState.completedNotes.size} notes"
                    else -> "En attente"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PitchTrail(
                points = uiState.pitchTrail,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            if (!uiState.isRecording && !uiState.isRefining && uiState.detectedKeyName != null) {
                Text(
                    "Tonalité détectée : ${uiState.detectedKeyName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            uiState.errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            TextButton(onClick = { showOptions = !showOptions }) {
                Text(
                    (if (showOptions) "Masquer les options" else "Options").uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (showOptions) {
                ExportOptions(uiState = uiState, viewModel = viewModel)
            }

            if (showBpmDialog) {
                ExportBpmDialog(
                    initialBpm = uiState.tapTempoBpm.takeIf { uiState.isTapTempoValidated } ?: uiState.exportBpm,
                    onConfirm = { bpm ->
                        showBpmDialog = false
                        viewModel.setExportBpm(bpm)
                        exportLauncher.launch("voicetomidi_${System.currentTimeMillis()}.mid")
                    },
                    onDismiss = { showBpmDialog = false }
                )
            }

            TextButton(onClick = { showHistory = !showHistory }) {
                Text(
                    (if (showHistory) "Masquer l'historique (${uiState.completedNotes.size})" else "Historique (${uiState.completedNotes.size})")
                        .uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (showHistory) {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(uiState.completedNotes.reversed()) { note ->
                        Text(
                            "${frenchNoteName(note.midiNote)} — ${note.durationMs} ms — vélocité ${note.velocity}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
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
            onPressEnd = viewModel::onHoldEnd
        )

        Text(
            text = when {
                uiState.isRecording && uiState.heldNoteCount == 1 -> "1 note marquée"
                uiState.isRecording && uiState.heldNoteCount > 0 -> "${uiState.heldNoteCount} notes marquées"
                uiState.isRecording -> "Maintiens le bouton pendant chaque note (facultatif)"
                else -> "Appuie et chante — ou lance l'enregistrement et chante librement"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Réglages d'export pour les utilisateurs avancés (quantification, vibrato, tempo). */
@Composable
private fun ExportOptions(uiState: RecordingUiState, viewModel: RecordingViewModel) {
    var showTapTempo by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.isPitchCorrectionEnabled,
                onCheckedChange = { viewModel.togglePitchCorrection() },
                enabled = !uiState.isRecording && !uiState.isRefining,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Text(
                "Rendre plus juste (si tu chantes entre deux notes)",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.isQuantizeEnabled,
                onCheckedChange = { viewModel.toggleQuantize() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Text(
                "Caler les notes sur le tempo (quantifier)",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.isPitchBendEnabled,
                onCheckedChange = { viewModel.togglePitchBend() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Text(
                "Vibrato (pitch bend)",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(onClick = { showTapTempo = !showTapTempo }) {
            Text(
                (if (uiState.isTapTempoValidated) {
                    "Tempo tapé : ${uiState.tapTempoBpm} BPM (validé)"
                } else {
                    "Tempo (tap tempo)"
                }).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (showTapTempo) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmoButton(text = "TAP", onClick = viewModel::onTap)

                Text(
                    uiState.tapTempoBpm?.let { bpm -> "$bpm BPM (${uiState.tapCount} tapotements)" }
                        ?: "Tapote au moins 2 fois au rythme voulu",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmoOutlinedButton(
                        text = if (uiState.isMetronomePlaying) "Arrêter" else "Écouter",
                        onClick = {
                            if (uiState.isMetronomePlaying) viewModel.stopTapTempoPreview() else viewModel.previewTapTempo()
                        },
                        enabled = uiState.tapTempoBpm != null
                    )
                    EmoOutlinedButton(
                        text = "Valider",
                        onClick = viewModel::validateTapTempo,
                        enabled = uiState.tapTempoBpm != null && !uiState.isTapTempoValidated
                    )
                    EmoOutlinedButton(
                        text = "Réinitialiser",
                        onClick = viewModel::resetTapTempo,
                        enabled = uiState.tapTempoBpm != null
                    )
                }
            }
        }
    }
}

/** Demande le tempo du projet FL Studio avant l'export, pour que le rythme tombe juste à l'import. */
@Composable
private fun ExportBpmDialog(initialBpm: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialBpm.toString()) }
    val bpm = text.toIntOrNull()?.takeIf { it in 40..300 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo de ton projet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mets le même BPM que ton projet FL Studio : les notes tomberont au bon endroit.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(3) },
                    label = { Text("BPM") },
                    singleLine = true,
                    isError = bpm == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { bpm?.let(onConfirm) }, enabled = bpm != null) { Text("EXPORTER") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ANNULER") }
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

