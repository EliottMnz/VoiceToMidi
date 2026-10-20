package com.example.voicetomidi.ui

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicetomidi.audio.AudioCaptureManager
import com.example.voicetomidi.audio.AudioSource
import com.example.voicetomidi.audio.DecodedAudio
import com.example.voicetomidi.audio.DemoScenario
import com.example.voicetomidi.audio.FileAudioSource
import com.example.voicetomidi.audio.MetronomeClicker
import com.example.voicetomidi.audio.SyntheticVoiceSource
import com.example.voicetomidi.audio.TakeDiagnostics
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.AnalysisSteps
import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.TapSegment
import com.example.voicetomidi.notes.TapTempoEstimator
import com.example.voicetomidi.notes.buildPitchBendCurve
import com.example.voicetomidi.notes.frequencyToMidiNote
import com.example.voicetomidi.notes.frenchNoteName
import com.example.voicetomidi.notes.quantize
import java.util.Collections
import kotlin.math.log2
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
import java.io.File

data class RecordingUiState(
    val isRecording: Boolean = false,
    /** Vrai brièvement après l'arrêt, pendant que l'analyse (MelodyAnalyzer) calcule les notes finales. */
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
    /** Option "Rendre plus juste" : recentre une voix qui chante entre deux notes ou qui dérive. */
    val isPitchCorrectionEnabled: Boolean = false,
    /** Tempo du projet FL Studio de l'utilisateur, écrit dans le MIDI exporté. */
    val exportBpm: Int = MidiFileWriter.DEFAULT_BPM,
    /** BPM calculé depuis les derniers tapotements (tap tempo), ou null si pas encore assez de données. */
    val tapTempoBpm: Int? = null,
    val tapCount: Int = 0,
    val isMetronomePlaying: Boolean = false,
    /** Si vrai, [tapTempoBpm] remplace l'estimation automatique du tempo pour la quantification. */
    val isTapTempoValidated: Boolean = false,
    /** Vrai tant que l'utilisateur maintient le gros bouton "note". */
    val isHolding: Boolean = false,
    /** Nombre de notes marquées au bouton pendant la prise en cours. */
    val heldNoteCount: Int = 0,
    /** Courbe de hauteur des [TRAIL_WINDOW_MS] dernières ms, pour l'affichage en direct. */
    val pitchTrail: List<TrailPoint> = emptyList(),
    /** Tonalité détectée sur la dernière prise (ex: "Do majeur"), ou null. */
    val detectedKeyName: String? = null
)

/** Bornes d'amplitude RMS utilisées pour normaliser la jauge visuelle (cohérent avec NoteSegmenter). */
private const val MIN_GAUGE_AMPLITUDE = 0.01f
private const val MAX_GAUGE_AMPLITUDE = 0.5f

/** En dessous, la mesure YIN est trop douteuse pour être tracée (évite les pics parasites). */
private const val TRAIL_MIN_CONFIDENCE = 0.5f

/** Confiance minimale pour afficher la tonalité détectée à l'utilisateur. */
private const val MIN_KEY_CONFIDENCE_TO_SHOW = 0.6

private const val MIN_EXPORT_BPM = 40
private const val MAX_EXPORT_BPM = 300

/** En dessous, un appui sur le bouton est un effleurement, pas une note. */
private const val MIN_TAP_MS = 80L

/** Rafraîchissement maximal de l'affichage en direct (~30 images/s). */
private const val UI_REFRESH_MS = 33L
private const val LAG_TAG = "VoiceToMidiLag"

private fun amplitudeToIntensity(rms: Float): Float {
    val clamped = rms.coerceIn(MIN_GAUGE_AMPLITUDE, MAX_GAUGE_AMPLITUDE)
    return (clamped - MIN_GAUGE_AMPLITUDE) / (MAX_GAUGE_AMPLITUDE - MIN_GAUGE_AMPLITUDE)
}

class RecordingViewModel : ViewModel() {

    private var pipeline: VoiceToMidiPipeline? = null
    private var captureJob: Job? = null

    // Historique brut de la session en cours, analysé en entier à l'arrêt (MelodyAnalyzer).
    // Voir NoteSegmenter (temps réel, hystérésis) vs MelodyAnalyzer (post-traitement).
    // Rempli par le fil de traitement du son, lu par le fil de l'interface : synchronisé.
    private val rawFrameLog: MutableList<RawPitchFrame> = Collections.synchronizedList(mutableListOf())

    // Appuis sur le gros bouton, sur la timeline audio (timestamps des frames, pas l'horloge
    // murale) : c'est ce qui permet de les comparer aux frames, même en démo accélérée.
    private val tapSegments = mutableListOf<TapSegment>()
    @Volatile
    private var holdStartMs: Long? = null
    @Volatile
    private var lastFrameTimestampMs = 0L

    // Horloge des appuis. Le son arrive par paquets, en retard sur le temps réel
    // (~300 ms mesurés sur une Galaxy Tab A8) : dater un appui avec "la dernière
    // frame analysée" le plaçait d'autant trop tôt dans l'enregistrement. On garde
    // plutôt l'ancre la plus fraîche observée : temps audio de fin de frame moins
    // instant de réception (× vitesse de lecture). Un appui à l'instant P
    // correspond alors au temps audio ancre + P × vitesse.
    @Volatile
    private var clockAnchorMs = Double.NEGATIVE_INFINITY
    private var clockSpeed = 1f
    private var clockWindowMs = 0.0

    private var clockSource: AudioSource? = null

    /**
     * Position, dans l'enregistrement, de l'instant présent. De préférence
     * l'horloge du micro lui-même (exacte, latence matérielle comprise) ; à
     * défaut (démo, fichier, appareil qui ne la fournit pas), l'ancre estimée.
     */
    private fun audioNowMs(): Long {
        clockSource?.audioPositionMsAt(System.nanoTime())?.let { return maxOf(it, 0L) }
        if (clockAnchorMs == Double.NEGATIVE_INFINITY) return lastFrameTimestampMs
        val now = clockAnchorMs + SystemClock.elapsedRealtime() * clockSpeed.toDouble()
        return maxOf(now.toLong(), 0L)
    }
    private val trail = ArrayDeque<TrailPoint>()

    // Frames recentrées sur les notes finales (vibrato seul), pour le pitch bend à l'export.
    private var vibratoFrames: List<RawPitchFrame> = emptyList()

    // Dernière prise analysée, pour la ré-analyser si l'option "Rendre plus juste" change.
    private var lastTakeFrames: List<RawPitchFrame> = emptyList()
    private var lastTakeTaps: List<TapSegment> = emptyList()

    private val tapTempoEstimator = TapTempoEstimator()
    private val metronome = MetronomeClicker()
    private var metronomeJob: Job? = null

    /** Dossier où chaque prise au micro est sauvegardée (son brut + mesures), ou null pour ne rien garder. */
    var diagnosticsDir: File? = null

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

    /**
     * Importe un fichier audio (ex: une prise du Dictaphone de l'iPhone) et
     * l'analyse comme une prise au micro. [decode] tourne hors du thread principal.
     */
    fun importAudio(decode: () -> DecodedAudio) {
        if (captureJob != null || _uiState.value.isRefining) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefining = true, errorMessage = null) }
            val audio = try {
                withContext(Dispatchers.IO) { decode() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefining = false, errorMessage = "Impossible de lire ce fichier : ${e.message}") }
                return@launch
            }
            _uiState.update { it.copy(isRefining = false) }
            start(FileAudioSource(audio))
        }
    }

    private fun start(source: AudioSource) {
        if (captureJob != null) return
        rawFrameLog.clear()
        tapSegments.clear()
        holdStartMs = null
        lastFrameTimestampMs = 0L
        clockAnchorMs = Double.NEGATIVE_INFINITY
        clockSpeed = source.playbackSpeed
        clockSource = source
        clockWindowMs = VoiceToMidiPipeline.WINDOW_SIZE * 1000.0 / source.sampleRate
        trail.clear()
        // Seules les vraies prises au micro sont gardées : la démo est connue d'avance.
        val takeDiagnostics = if (source is AudioCaptureManager && diagnosticsDir != null) TakeDiagnostics(source.sampleRate) else null
        val newPipeline = VoiceToMidiPipeline(audioSource = source, rawAudioSink = takeDiagnostics?.let { it::append })
        pipeline = newPipeline
        _uiState.update {
            RecordingUiState(
                isRecording = true,
                completedNotes = it.completedNotes,
                isQuantizeEnabled = it.isQuantizeEnabled,
                isPitchBendEnabled = it.isPitchBendEnabled,
                isPitchCorrectionEnabled = it.isPitchCorrectionEnabled,
                exportBpm = it.exportBpm,
                tapTempoBpm = it.tapTempoBpm,
                tapCount = it.tapCount,
                isTapTempoValidated = it.isTapTempoValidated
            )
        }

        captureJob = viewModelScope.launch {
            try {
                // Le traitement de chaque frame se fait hors du fil de l'interface, et
                // l'écran n'est rafraîchi qu'au plus toutes les [UI_REFRESH_MS] avec
                // l'état le plus récent : avant, ~86 mises à jour par seconde sur le fil
                // principal s'accumulaient en file d'attente, et la courbe affichait la
                // voix avec un retard croissant.
                var lastUiRefresh = 0L
                var lagSum = 0.0
                var lagCount = 0
                var lastLagLog = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Default) { newPipeline.run().collect { frame ->
                    rawFrameLog.add(
                        RawPitchFrame(
                            timestampMs = frame.timestampMs,
                            frequencyHz = frame.pitch.frequencyHz,
                            confidence = frame.pitch.confidence,
                            rmsAmplitude = frame.rmsAmplitude
                        )
                    )
                    lastFrameTimestampMs = frame.timestampMs
                    val anchor = frame.timestampMs + clockWindowMs - SystemClock.elapsedRealtime() * clockSpeed.toDouble()
                    if (anchor > clockAnchorMs) clockAnchorMs = anchor
                    val voiced = frame.pitch.confidence > 0f
                    val reliable = frame.pitch.confidence >= TRAIL_MIN_CONFIDENCE && frame.pitch.frequencyHz > 0f
                    trail.addLast(
                        TrailPoint(
                            timestampMs = frame.timestampMs,
                            semitones = if (reliable) 69 + 12 * log2(frame.pitch.frequencyHz / 440f) else null,
                            isHeld = holdStartMs != null
                        )
                    )
                    while (trail.first().timestampMs < frame.timestampMs - TRAIL_WINDOW_MS) trail.removeFirst()

                    val now = SystemClock.elapsedRealtime()
                    lagSum += audioNowMs() - (frame.timestampMs + clockWindowMs)
                    lagCount++
                    if (now - lastLagLog >= 2000) {
                        Log.d(LAG_TAG, "retard de traitement moyen : ${(lagSum / lagCount).toInt()} ms")
                        lagSum = 0.0
                        lagCount = 0
                        lastLagLog = now
                    }
                    if (frame.completedNote == null && now - lastUiRefresh < UI_REFRESH_MS) return@collect
                    lastUiRefresh = now

                    val trailSnapshot = trail.toList()
                    _uiState.update { current ->
                        current.copy(
                            currentNoteName = if (voiced) frenchNoteName(frequencyToMidiNote(frame.pitch.frequencyHz)) else null,
                            currentFrequencyHz = if (voiced) frame.pitch.frequencyHz else null,
                            currentTrackedMidiNote = frame.trackedNote,
                            currentIntensity = if (frame.trackedNote != null) amplitudeToIntensity(frame.rmsAmplitude) else 0f,
                            pitchTrail = trailSnapshot,
                            completedNotes = frame.completedNote?.let { current.completedNotes + it }
                                ?: current.completedNotes
                        )
                    }
                } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ex: AudioRecord indisponible (micro pris par une autre appli), erreur
                // matérielle... On informe l'UI au lieu de laisser planter l'app.
                _uiState.update { it.copy(errorMessage = e.message ?: "Erreur pendant la capture audio") }
            }

            // Bouton encore maintenu au moment de l'arrêt : la note se termine là.
            closeHeldNote()

            _uiState.update {
                it.copy(
                    isRecording = false,
                    isRefining = true,
                    isHolding = false,
                    currentNoteName = null,
                    currentFrequencyHz = null,
                    currentTrackedMidiNote = null,
                    currentIntensity = 0f
                )
            }

            // Recalcule les notes finales hors-ligne sur toute la session (justesse, découpage,
            // tonalité, correction) : remplace la liste temps réel par une version propre,
            // utilisée aussi bien pour l'historique affiché que pour l'export MIDI.
            val frames = synchronized(rawFrameLog) { rawFrameLog.toList() }
            val taps = tapSegments.toList()
            lastTakeFrames = frames
            lastTakeTaps = taps
            val analysis = withContext(Dispatchers.Default) {
                try {
                    analyzer().analyze(frames, taps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = "Échec de l'analyse des notes : ${e.message}") }
                    null
                }
            }
            vibratoFrames = analysis?.vibratoFrames ?: emptyList()
            val dir = diagnosticsDir
            if (takeDiagnostics != null && dir != null) {
                withContext(Dispatchers.IO) {
                    try {
                        takeDiagnostics.save(dir, frames, taps, analysis?.notes ?: emptyList())
                    } catch (e: Exception) {
                        // Le diagnostic ne doit jamais empêcher d'utiliser la prise.
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isRefining = false,
                    completedNotes = analysis?.notes ?: emptyList(),
                    errorMessage = if (analysis?.inputTooQuiet == true) {
                        "Je n'ai rien entendu : le son capté est trop faible. Vérifie que le micro est bien actif et chante plus près."
                    } else {
                        it.errorMessage
                    },
                    // Sous ce seuil (ex: 2-3 notes), afficher une tonalité serait trompeur.
                    detectedKeyName = analysis?.key?.takeIf { key -> key.confidence >= MIN_KEY_CONFIDENCE_TO_SHOW }?.displayName
                )
            }

            captureJob = null
        }
    }

    fun stopRecording() {
        pipeline?.stop()
    }

    /**
     * Début d'une note marquée au bouton. Si rien n'enregistre encore, l'appui
     * lance l'enregistrement : un seul geste suffit pour commencer à chanter.
     */
    fun onHoldStart() {
        if (_uiState.value.isRefining) return
        if (captureJob == null) startRecording()
        holdStartMs = audioNowMs()
        _uiState.update { it.copy(isHolding = true) }
    }

    /** Fin de la note marquée au bouton. */
    fun onHoldEnd() {
        closeHeldNote()
        _uiState.update { it.copy(isHolding = false, heldNoteCount = tapSegments.size) }
    }

    private fun closeHeldNote() {
        val start = holdStartMs ?: return
        holdStartMs = null
        val end = audioNowMs()
        // Appui très bref (ex : celui qui lance l'enregistrement) : ce n'est pas une note.
        if (end - start >= MIN_TAP_MS) tapSegments += TapSegment(start, end)
    }

    fun clearNotes() {
        rawFrameLog.clear()
        vibratoFrames = emptyList()
        _uiState.update { it.copy(completedNotes = emptyList(), detectedKeyName = null, heldNoteCount = 0) }
    }

    fun toggleQuantize() {
        _uiState.update { it.copy(isQuantizeEnabled = !it.isQuantizeEnabled) }
    }

    private fun analyzer() = MelodyAnalyzer(
        steps = if (_uiState.value.isPitchCorrectionEnabled) AnalysisSteps.CORRECTED else AnalysisSteps.AS_SUNG
    )

    /**
     * Active/désactive "Rendre plus juste" et ré-analyse aussitôt la dernière
     * prise, pour comparer les deux résultats sans rechanter.
     */
    fun togglePitchCorrection() {
        if (_uiState.value.isRecording || _uiState.value.isRefining) return
        _uiState.update { it.copy(isPitchCorrectionEnabled = !it.isPitchCorrectionEnabled) }
        val frames = lastTakeFrames
        if (frames.isEmpty()) return
        val taps = lastTakeTaps
        viewModelScope.launch {
            _uiState.update { it.copy(isRefining = true) }
            val analysis = withContext(Dispatchers.Default) { analyzer().analyze(frames, taps) }
            vibratoFrames = analysis.vibratoFrames
            _uiState.update {
                it.copy(
                    isRefining = false,
                    completedNotes = analysis.notes,
                    detectedKeyName = analysis.key?.takeIf { key -> key.confidence >= MIN_KEY_CONFIDENCE_TO_SHOW }?.displayName
                )
            }
        }
    }

    fun setExportBpm(bpm: Int) {
        _uiState.update { it.copy(exportBpm = bpm.coerceIn(MIN_EXPORT_BPM, MAX_EXPORT_BPM)) }
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

        // Le MIDI est écrit au tempo du projet de l'utilisateur : importé dans un
        // projet FL Studio à ce même tempo, chaque note tombe à son instant réel.
        // (Un MIDI à 120 BPM importé dans un projet à 140 aurait un rythme faux.)
        // La quantification, si elle est cochée, cale sur les doubles-croches de ce tempo.
        val bpm = state.exportBpm
        val outputNotes = if (state.isQuantizeEnabled) quantize(originalNotes, gridMs = 60_000.0 / bpm / 4) else originalNotes

        val pitchBends = if (state.isPitchBendEnabled) {
            buildPitchBendCurve(originalNotes, outputNotes, vibratoFrames)
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
