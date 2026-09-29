package com.example.voicetomidi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.voicetomidi.audio.DemoScenario
import com.example.voicetomidi.notes.midiNoteName
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
    var showHistory by remember { mutableStateOf(false) }
    var showDemoMenu by remember { mutableStateOf(false) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

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
        Text(
            "VOICE TO MIDI",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        if (!hasPermission) {
            Text(
                "Micro non autorisé — utilise le mode démo pour tester sans chanter, ou autorise le micro pour enregistrer ta voix.",
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
                    text = "Autoriser le micro",
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
            Box {
                EmoOutlinedButton(
                    text = "Mode démo",
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
                onClick = { exportLauncher.launch("voicetomidi_${System.currentTimeMillis()}.mid") },
                enabled = uiState.completedNotes.isNotEmpty() && !uiState.isRecording && !uiState.isRefining
            )
        }

        Text(
            text = when {
                uiState.isRefining -> "Analyse en cours…"
                uiState.currentNoteName != null -> "${uiState.currentNoteName} — ${"%.1f".format(uiState.currentFrequencyHz ?: 0f)} Hz"
                uiState.isRecording -> "…"
                else -> "En attente"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outline)

        PianoRollVisualizer(
            activeMidiNote = uiState.currentTrackedMidiNote,
            intensity = uiState.currentIntensity,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

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
                        "${midiNoteName(note.midiNote)} — ${note.durationMs} ms — vélocité ${note.velocity}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
