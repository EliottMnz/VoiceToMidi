package com.example.voicetomidi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.frequencyToMidiNote
import com.example.voicetomidi.notes.midiNoteName
import com.example.voicetomidi.pipeline.VoiceToMidiPipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordingUiState(
    val isRecording: Boolean = false,
    val currentNoteName: String? = null,
    val currentFrequencyHz: Float? = null,
    val completedNotes: List<NoteEvent> = emptyList()
)

class RecordingViewModel : ViewModel() {

    private val pipeline = VoiceToMidiPipeline()
    private var captureJob: Job? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (captureJob != null) return
        _uiState.update { RecordingUiState(isRecording = true, completedNotes = it.completedNotes) }

        captureJob = viewModelScope.launch {
            pipeline.run().collect { frame ->
                val voiced = frame.pitch.confidence > 0f
                _uiState.update { current ->
                    current.copy(
                        currentNoteName = if (voiced) midiNoteName(frequencyToMidiNote(frame.pitch.frequencyHz)) else null,
                        currentFrequencyHz = if (voiced) frame.pitch.frequencyHz else null,
                        completedNotes = frame.completedNote?.let { current.completedNotes + it }
                            ?: current.completedNotes
                    )
                }
            }
            _uiState.update { it.copy(isRecording = false, currentNoteName = null, currentFrequencyHz = null) }
            captureJob = null
        }
    }

    fun stopRecording() {
        pipeline.stop()
    }

    fun clearNotes() {
        _uiState.update { it.copy(completedNotes = emptyList()) }
    }

    fun buildMidiFile(): ByteArray = MidiFileWriter().write(_uiState.value.completedNotes)

    override fun onCleared() {
        pipeline.stop()
        super.onCleared()
    }
}
