package com.example.voicetomidi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicetomidi.audio.AudioCaptureManager
import com.example.voicetomidi.audio.AudioSource
import com.example.voicetomidi.audio.DemoScenario
import com.example.voicetomidi.audio.MetronomeClicker
import com.example.voicetomidi.audio.SyntheticVoiceSource
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.NoScaleConstraint
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.ScaleConstraint
import com.example.voicetomidi.notes.TapTempoEstimator
import com.example.voicetomidi.notes.ViterbiNoteSegmenter
import com.example.voicetomidi.notes.buildPitchBendCurve
import com.example.voicetomidi.notes.estimateGridMs
import com.example.voicetomidi.notes.frequencyToMidiNote
import com.example.voicetomidi.notes.midiNoteName
import com.example.voicetomidi.notes.quantize
import com.example.voicetomidi.pipeline.VoiceToMidiPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingUiState(
    val isRecording: Boolean = false,
    /** Vrai brièvement après l'arrêt, pendant que le HMM/Viterbi recalcule les notes finales. */
    val isRefining: Boolean = false,
    val currentNoteName: String? = null,
    val currentFrequencyHz: Float? = null,
    /** Note stabilisée par le NoteSegmenter (confirmée), pour un affichage visuel stable. */
    val currentTrackedMidiNote: Int? = null,
    /** Intensité normalisée [0, 1] de la note tenue, pour une jauge visuelle. */
    val currentIntensity: Float = 0f,
    val completedNotes: List<NoteEvent> = emptyList(),
    /** Message si la capture a échoué (micro indisponible, etc.) ; effacé au prochain démarrage. */
    val errorMessage: String? = null,
    /** Cale les notes sur une grille de doubles-croches à l'export, au lieu des timings réels. */
    val isQuantizeEnabled: Boolean = false,
    /** Préserve le vibrato/l'imprécision de la voix à l'export via des messages MIDI Pitch Bend. */
    val isPitchBendEnabled: Boolean = false,
    /** BPM calculé depuis les derniers tapotements (tap tempo), ou null si pas encore assez de données. */
    val tapTempoBpm: Int? = null,
    val tapCount: Int = 0,
    val isMetronomePlaying: Boolean = false,
    /** Si vrai, [tapTempoBpm] remplace l'estimation automatique du tempo pour la quantification. */
    val isTapTempoValidated: Boolean = false,
    /** Gamme choisie avant l'enregistrement — restreint les notes candidates pour moins d'ambiguïté. */
    val selectedScale: ScaleConstraint = NoScaleConstraint
)

/** Bornes d'amplitude RMS utilisées pour normaliser la jauge visuelle (cohérent avec NoteSegmenter). */
private const val MIN_GAUGE_AMPLITUDE = 0.01f
private const val MAX_GAUGE_AMPLITUDE = 0.5f

private fun amplitudeToIntensity(rms: Float): Float {
    val clamped = rms.coerceIn(MIN_GAUGE_AMPLITUDE, MAX_GAUGE_AMPLITUDE)
    return (clamped - MIN_GAUGE_AMPLITUDE) / (MAX_GAUGE_AMPLITUDE - MIN_GAUGE_AMPLITUDE)
}

/** Convertit un pas de grille (double-croche, [estimateGridMs]) en BPM équivalent, borné à une plage musicale raisonnable. */
private fun bpmFromGridMs(gridMs: Double): Int {
    val beatMs = gridMs * 4
    return (60_000.0 / beatMs).toInt().coerceIn(40, 240)
}

class RecordingViewModel : ViewModel() {

    private var pipeline: VoiceToMidiPipeline? = null
    private var captureJob: Job? = null

    // Historique brut de la session en cours, pour le raffinage HMM/Viterbi à l'arrêt.
    // Voir NoteSegmenter (temps réel, hystérésis) vs ViterbiNoteSegmenter (post-traitement).
    private val rawFrameLog = mutableListOf<RawPitchFrame>()

    private val tapTempoEstimator = TapTempoEstimator()
    private val metronome = MetronomeClicker()
    private var metronomeJob: Job? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    /** Capture réelle au micro (nécessite la permission RECORD_AUDIO). */
    fun startRecording() = start(AudioCaptureManager())

    /**
     * Rejoue un [DemoScenario] à la place du micro — pour tester tout le
     * pipeline (clavier, export) sans chanter, ex. dans un lieu où on ne peut
     * pas faire de bruit. Aucune permission requise.
     */
    fun startDemo(scenario: DemoScenario = DemoScenario.STANDARD) = start(SyntheticVoiceSource(scenario = scenario))

    private fun start(source: AudioSource) {
        if (captureJob != null) return
        rawFrameLog.clear()
        val scale = _uiState.value.selectedScale
        val newPipeline = VoiceToMidiPipeline(audioSource = source, scaleConstraint = scale)
        pipeline = newPipeline
        _uiState.update {
            RecordingUiState(
                isRecording = true,
                completedNotes = it.completedNotes,
                isQuantizeEnabled = it.isQuantizeEnabled,
                isPitchBendEnabled = it.isPitchBendEnabled,
                selectedScale = it.selectedScale
            )
        }

        captureJob = viewModelScope.launch {
            try {
                newPipeline.run().collect { frame ->
                    rawFrameLog.add(
                        RawPitchFrame(
                            timestampMs = frame.timestampMs,
                            frequencyHz = frame.pitch.frequencyHz,
                            confidence = frame.pitch.confidence,
                            rmsAmplitude = frame.rmsAmplitude
                        )
                    )
                    val voiced = frame.pitch.confidence > 0f
                    _uiState.update { current ->
                        current.copy(
                            currentNoteName = if (voiced) midiNoteName(frequencyToMidiNote(frame.pitch.frequencyHz)) else null,
                            currentFrequencyHz = if (voiced) frame.pitch.frequencyHz else null,
                            currentTrackedMidiNote = frame.trackedNote,
                            currentIntensity = if (frame.trackedNote != null) amplitudeToIntensity(frame.rmsAmplitude) else 0f,
                            completedNotes = frame.completedNote?.let { current.completedNotes + it }
                                ?: current.completedNotes
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ex: AudioRecord indisponible (micro pris par une autre appli), erreur
                // matérielle... On informe l'UI au lieu de laisser planter l'app.
                _uiState.update { it.copy(errorMessage = e.message ?: "Erreur pendant la capture audio") }
            }

            _uiState.update {
                it.copy(
                    isRecording = false,
                    isRefining = true,
                    currentNoteName = null,
                    currentFrequencyHz = null,
                    currentTrackedMidiNote = null,
                    currentIntensity = 0f
                )
            }

            // Recalcule les notes finales hors-ligne (HMM/Viterbi sur toute la session) :
            // remplace la liste temps réel par une version plus propre, utilisée aussi bien
            // pour l'historique affiché que pour l'export MIDI.
            val refinedNotes = withContext(Dispatchers.Default) {
                try {
                    ViterbiNoteSegmenter(scaleConstraint = scale).segment(rawFrameLog)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = "Échec de l'analyse des notes : ${e.message}") }
                    emptyList()
                }
            }
            _uiState.update { it.copy(isRefining = false, completedNotes = refinedNotes) }

            captureJob = null
        }
    }

    fun stopRecording() {
        pipeline?.stop()
    }

    fun clearNotes() {
        rawFrameLog.clear()
        _uiState.update { it.copy(completedNotes = emptyList()) }
    }

    /** Choisit la gamme à utiliser pour la prochaine prise — verrouillé pendant l'enregistrement. */
    fun setScale(scale: ScaleConstraint) {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(selectedScale = scale) }
    }

    fun toggleQuantize() {
        _uiState.update { it.copy(isQuantizeEnabled = !it.isQuantizeEnabled) }
    }

    fun togglePitchBend() {
        _uiState.update { it.copy(isPitchBendEnabled = !it.isPitchBendEnabled) }
    }

    /** Enregistre un tapotement au tempo voulu par l'utilisateur (tap tempo). */
    fun onTap() {
        val bpm = tapTempoEstimator.tap(System.currentTimeMillis())
        _uiState.update {
            it.copy(tapTempoBpm = bpm, tapCount = tapTempoEstimator.tapCount(), isTapTempoValidated = false)
        }
    }

    /** Joue un clic de métronome au tempo tapé, pour le valider à l'oreille avant de l'utiliser. */
    fun previewTapTempo() {
        val bpm = _uiState.value.tapTempoBpm ?: return
        metronomeJob?.cancel()
        metronomeJob = viewModelScope.launch {
            _uiState.update { it.copy(isMetronomePlaying = true) }
            try {
                metronome.play(bpm)
            } finally {
                _uiState.update { it.copy(isMetronomePlaying = false) }
            }
        }
    }

    fun stopTapTempoPreview() {
        metronomeJob?.cancel()
        metronomeJob = null
        _uiState.update { it.copy(isMetronomePlaying = false) }
    }

    /** Confirme le tempo tapé : il remplacera l'estimation automatique pour la quantification. */
    fun validateTapTempo() {
        if (_uiState.value.tapTempoBpm != null) {
            _uiState.update { it.copy(isTapTempoValidated = true) }
        }
    }

    fun resetTapTempo() {
        stopTapTempoPreview()
        tapTempoEstimator.reset()
        _uiState.update { it.copy(tapTempoBpm = null, tapCount = 0, isTapTempoValidated = false) }
    }

    fun buildMidiFile(): ByteArray {
        val state = _uiState.value
        val originalNotes = state.completedNotes

        // Tempo du fichier MIDI exporté, quand la quantification est active : le tempo
        // tapé et validé par l'utilisateur s'il y en a un, sinon celui détecté
        // automatiquement depuis la performance. Sert aussi de grille pour que
        // l'affichage soit visuellement aligné dans un DAW.
        val outputNotes: List<NoteEvent>
        val bpm: Int
        if (state.isQuantizeEnabled) {
            val validatedBpm = state.tapTempoBpm.takeIf { state.isTapTempoValidated }
            val gridMs = if (validatedBpm != null) 60_000.0 / validatedBpm / 4 else estimateGridMs(originalNotes)
            bpm = validatedBpm ?: bpmFromGridMs(gridMs)
            outputNotes = quantize(originalNotes, gridMs = gridMs)
        } else {
            bpm = MidiFileWriter.DEFAULT_BPM
            outputNotes = originalNotes
        }

        val pitchBends = if (state.isPitchBendEnabled) {
            buildPitchBendCurve(originalNotes, outputNotes, rawFrameLog)
        } else {
            emptyList()
        }
        return MidiFileWriter(beatsPerMinute = bpm).write(outputNotes, pitchBends)
    }

    override fun onCleared() {
        pipeline?.stop()
        metronome.release()
        super.onCleared()
    }
}
