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
import com.example.voicetomidi.audio.Metronome
import com.example.voicetomidi.audio.SyntheticVoiceSource
import com.example.voicetomidi.audio.TakeDiagnostics
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.AnalysisSteps
import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.ScaleConstraint
import com.example.voicetomidi.notes.TapSegment
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
import kotlinx.coroutines.delay
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
    /**
     * Tempo du projet FL Studio, s'il a été donné avant la prise. Il déclenche
     * un décompte de [COUNT_IN_BEATS] clics avant de chanter, ce qui place les
     * temps dans l'enregistrement : la quantification n'a de sens qu'avec lui.
     */
    val projectBpm: Int? = null,
    /** Garde le métronome pendant toute la prise (sinon : décompte seul). */
    val isMetronomeEnabled: Boolean = false,
    /** Temps du décompte en cours (1 à [COUNT_IN_BEATS]), ou null hors décompte. */
    val countInBeat: Int? = null,
    /** Cale les notes sur les doubles-croches de [projectBpm] à l'export. */
    val isQuantizeEnabled: Boolean = false,
    /** Préserve le vibrato/l'imprécision de la voix à l'export via des messages MIDI Pitch Bend. */
    val isPitchBendEnabled: Boolean = false,
    /** Option "Rendre plus juste" : recentre une voix qui chante entre deux notes ou qui dérive. */
    val isPitchCorrectionEnabled: Boolean = false,
    /** Option "Gamme de mon projet" : null = automatique (rien d'imposé). */
    val projectScale: ScaleConstraint? = null,
    /** Tempo écrit dans le MIDI exporté (demandé à l'export, pré-rempli avec [projectBpm]). */
    val exportBpm: Int = MidiFileWriter.DEFAULT_BPM,
    /** Vrai tant que l'utilisateur maintient le gros bouton "note". */
    val isHolding: Boolean = false,
    /** Nombre de notes marquées au bouton pendant la prise en cours. */
    val heldNoteCount: Int = 0,
    /** Courbe de hauteur des [TRAIL_WINDOW_MS] dernières ms, pour l'affichage en direct. */
    val pitchTrail: List<TrailPoint> = emptyList(),
    /** Tonalité détectée sur la dernière prise (ex: "Do majeur"), ou null. */
    val detectedKeyName: String? = null,
    /**
     * Courbe de toute la dernière prise, donnée une fois l'analyse finie : la
     * courbe se dézoome alors pour montrer la mélodie en entier. Null pendant
     * la prise, ou si rien n'a été chanté.
     */
    val takeTrail: List<TrailPoint>? = null
)

/** Bornes d'amplitude RMS utilisées pour normaliser la jauge visuelle (cohérent avec NoteSegmenter). */
private const val MIN_GAUGE_AMPLITUDE = 0.01f
private const val MAX_GAUGE_AMPLITUDE = 0.5f

/** En dessous, la mesure YIN est trop douteuse pour être tracée (évite les pics parasites). */
private const val TRAIL_MIN_CONFIDENCE = 0.5f

/** Confiance minimale pour afficher la tonalité détectée à l'utilisateur. */
private const val MIN_KEY_CONFIDENCE_TO_SHOW = 0.6

const val MIN_EXPORT_BPM = 40
const val MAX_EXPORT_BPM = 300

/** Clics de décompte avant de chanter quand un tempo est donné. */
const val COUNT_IN_BEATS = 4

/** En dessous, un appui sur le bouton est un effleurement, pas une note. */
private const val MIN_TAP_MS = 80L

/** Rafraîchissement maximal de l'affichage en direct (~30 images/s). */
private const val UI_REFRESH_MS = 33L
private const val COUNT_IN_REFRESH_MS = 20L
private const val LAG_TAG = "VoiceToMidiLag"

/** Point de courbe d'une frame : hauteur en demi-tons MIDI, ou null si la hauteur n'est pas fiable. */
private fun trailPoint(timestampMs: Long, frequencyHz: Float, confidence: Float, isHeld: Boolean) = TrailPoint(
    timestampMs = timestampMs,
    semitones = if (confidence >= TRAIL_MIN_CONFIDENCE && frequencyHz > 0f) 69 + 12 * log2(frequencyHz / 440f) else null,
    isHeld = isHeld
)

/** Courbe de toute une prise (pour la vue d'ensemble), ou null si aucune hauteur fiable. */
private fun buildTakeTrail(frames: List<RawPitchFrame>, taps: List<TapSegment>): List<TrailPoint>? {
    val points = frames.map { f ->
        trailPoint(f.timestampMs, f.frequencyHz, f.confidence, isHeld = taps.any { f.timestampMs in it.startMs..it.endMs })
    }
    return points.takeIf { list -> list.any { it.semitones != null } }
}

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

    private var metronome: Metronome? = null

    // Position du 1er temps après le décompte dans la dernière prise (ms) : le MIDI
    // exporté commence là, sur le temps, et la quantification se cale dessus.
    private var beatOriginMs = 0L

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
                projectBpm = it.projectBpm,
                isMetronomeEnabled = it.isMetronomeEnabled,
                isQuantizeEnabled = it.isQuantizeEnabled,
                isPitchBendEnabled = it.isPitchBendEnabled,
                isPitchCorrectionEnabled = it.isPitchCorrectionEnabled,
                projectScale = it.projectScale,
                exportBpm = it.exportBpm
            )
        }

        // Tempo donné : décompte (et métronome si demandé), joué en même temps que le micro
        // tourne. Seulement au micro : un fichier importé ou la démo ont leur propre rythme.
        beatOriginMs = 0L
        val bpm = _uiState.value.projectBpm
        val click = if (bpm != null && source is AudioCaptureManager) Metronome() else null
        metronome = click
        if (click != null && bpm != null) {
            val keepClicking = _uiState.value.isMetronomeEnabled
            viewModelScope.launch(Dispatchers.Default) { click.play(bpm, COUNT_IN_BEATS, keepClicking) }
            viewModelScope.launch {
                while (metronome === click) {
                    val beat = click.beatAt(System.nanoTime())
                    if (beat >= COUNT_IN_BEATS) break
                    _uiState.update { it.copy(countInBeat = if (beat < 0) null else COUNT_IN_BEATS - beat) }
                    delay(COUNT_IN_REFRESH_MS)
                }
                _uiState.update { it.copy(countInBeat = null) }
            }
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
                    trail.addLast(trailPoint(frame.timestampMs, frame.pitch.frequencyHz, frame.pitch.confidence, isHeld = holdStartMs != null))
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

            if (click != null) {
                click.stop()
                // Instant où le 1er temps après le décompte a été entendu, placé dans
                // l'enregistrement par l'horloge du micro (les deux latences comprises).
                beatOriginMs = source.audioPositionMsAt(click.beatNanoTime(COUNT_IN_BEATS))?.coerceAtLeast(0L) ?: 0L
                metronome = null
            }

            _uiState.update {
                it.copy(
                    isRecording = false,
                    isRefining = true,
                    isHolding = false,
                    countInBeat = null,
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
            val takeTrail = withContext(Dispatchers.Default) { buildTakeTrail(frames, taps) }
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
                    takeTrail = takeTrail,
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
        _uiState.update {
            it.copy(completedNotes = emptyList(), detectedKeyName = null, heldNoteCount = 0, pitchTrail = emptyList(), takeTrail = null)
        }
    }

    fun toggleQuantize() {
        _uiState.update { it.copy(isQuantizeEnabled = !it.isQuantizeEnabled) }
    }

    private fun analyzer() = MelodyAnalyzer(
        steps = if (_uiState.value.isPitchCorrectionEnabled) AnalysisSteps.CORRECTED else AnalysisSteps.AS_SUNG,
        scale = _uiState.value.projectScale
    )

    /** Active/désactive "Rendre plus juste" (la dernière prise est ré-analysée aussitôt). */
    fun togglePitchCorrection() {
        if (_uiState.value.isRecording || _uiState.value.isRefining) return
        _uiState.update { it.copy(isPitchCorrectionEnabled = !it.isPitchCorrectionEnabled) }
        reanalyzeLastTake()
    }

    /** Choisit la gamme du projet (null = automatique) ; la dernière prise est ré-analysée aussitôt. */
    fun setProjectScale(scale: ScaleConstraint?) {
        if (_uiState.value.isRecording || _uiState.value.isRefining) return
        _uiState.update { it.copy(projectScale = scale) }
        reanalyzeLastTake()
    }

    /** Ré-analyse la dernière prise avec les options actuelles, pour comparer sans rechanter. */
    private fun reanalyzeLastTake() {
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

    /** Tempo du projet donné avant la prise (null = aucun : pas de décompte ni de quantification). */
    fun setProjectBpm(bpm: Int?) {
        _uiState.update {
            it.copy(
                projectBpm = bpm,
                exportBpm = bpm ?: it.exportBpm,
                isMetronomeEnabled = it.isMetronomeEnabled && bpm != null,
                isQuantizeEnabled = it.isQuantizeEnabled && bpm != null
            )
        }
    }

    fun toggleMetronome() {
        _uiState.update { it.copy(isMetronomeEnabled = !it.isMetronomeEnabled && it.projectBpm != null) }
    }

    fun buildMidiFile(): ByteArray {
        val state = _uiState.value

        // Après un décompte, le MIDI commence sur le 1er temps : ce qui précède
        // (le décompte) est coupé, et le reste est décalé d'autant.
        val origin = beatOriginMs
        val originalNotes = state.completedNotes.filter { it.endTimeMs > origin }
        val alignedNotes = originalNotes.map {
            it.copy(startTimeMs = (it.startTimeMs - origin).coerceAtLeast(0L), endTimeMs = it.endTimeMs - origin)
        }

        // Le MIDI est écrit au tempo du projet de l'utilisateur : importé dans un
        // projet FL Studio à ce même tempo, chaque note tombe à son instant réel.
        // La quantification n'est proposée qu'avec un tempo donné avant la prise :
        // sans décompte, la grille ne tomberait pas sur les temps chantés.
        val bpm = state.exportBpm
        val quantizeOn = state.isQuantizeEnabled && state.projectBpm != null
        val outputNotes = if (quantizeOn) quantize(alignedNotes, gridMs = 60_000.0 / bpm / 4) else alignedNotes

        val pitchBends = if (state.isPitchBendEnabled) {
            buildPitchBendCurve(originalNotes, outputNotes, vibratoFrames)
        } else {
            emptyList()
        }
        return MidiFileWriter(beatsPerMinute = bpm).write(outputNotes, pitchBends)
    }

    override fun onCleared() {
        pipeline?.stop()
        metronome?.stop()
        super.onCleared()
    }
}
