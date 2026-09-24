package com.example.voicetomidi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.voicetomidi.notes.midiNoteName
import java.io.IOException

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordingViewModel = viewModel(),
    onChangeTheme: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onChangeTheme) {
                Text("🎨 Thème")
            }
        }

        if (!hasPermission) {
            Text("VoiceToMidi a besoin du micro pour détecter les notes chantées.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Autoriser le micro")
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (uiState.isRecording) viewModel.stopRecording() else viewModel.startRecording()
            }) {
                Text(if (uiState.isRecording) "Arrêter" else "Enregistrer")
            }
            OutlinedButton(onClick = viewModel::clearNotes, enabled = uiState.completedNotes.isNotEmpty()) {
                Text("Effacer")
            }
            Button(
                onClick = { exportLauncher.launch("voicetomidi_${System.currentTimeMillis()}.mid") },
                enabled = uiState.completedNotes.isNotEmpty() && !uiState.isRecording
            ) {
                Text("Exporter en .mid")
            }
        }

        Text(
            text = uiState.currentNoteName?.let { name ->
                val freq = uiState.currentFrequencyHz ?: 0f
                "Note actuelle : $name (${"%.1f".format(freq)} Hz)"
            } ?: if (uiState.isRecording) "..." else "En attente",
            style = MaterialTheme.typography.titleMedium
        )

        HorizontalDivider(Modifier.fillMaxWidth())

        Text("Notes détectées (${uiState.completedNotes.size})", style = MaterialTheme.typography.titleSmall)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(uiState.completedNotes.reversed()) { note ->
                Text("${midiNoteName(note.midiNote)} — ${note.durationMs} ms — vélocité ${note.velocity}")
            }
        }
    }
}
