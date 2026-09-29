package com.example.voicetomidi.pipeline

import com.example.voicetomidi.audio.AudioCaptureManager
import com.example.voicetomidi.audio.AudioSource
import com.example.voicetomidi.audio.toAnalysisWindows
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.NoteSegmenter
import com.example.voicetomidi.pitch.PitchResult
import com.example.voicetomidi.pitch.YinPitchDetector
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Un instantané du pipeline pour une fenêtre d'analyse donnée.
 * [completedNote] n'est non-null que sur la frame où une note vient de se clore.
 * [trackedNote] est la note *stabilisée* par [NoteSegmenter] (confirmée, avec
 * hystérésis), à distinguer de [pitch] qui est la lecture brute frame par
 * frame — c'est [trackedNote] qu'il faut utiliser pour un affichage visuel
 * stable (ex: clavier), [pitch] pour une lecture précise en Hz.
 */
data class LiveFrame(
    val timestampMs: Long,
    val pitch: PitchResult,
    val rmsAmplitude: Float,
    val trackedNote: Int?,
    val completedNote: NoteEvent?
)

/**
 * Relie capture micro -> fenêtrage -> détection de pitch (YIN) + énergie (RMS)
 * -> segmentation en notes. Émet une [LiveFrame] par fenêtre d'analyse, ce qui
 * permet à l'UI d'afficher un retour temps réel (note courante) en plus de
 * récupérer les [NoteEvent] au fur et à mesure qu'ils se terminent.
 */
class VoiceToMidiPipeline(
    private val audioSource: AudioSource = AudioCaptureManager(),
    private val windowSize: Int = WINDOW_SIZE,
    private val hopSize: Int = HOP_SIZE
) {
    private val sampleRate get() = audioSource.sampleRate
    private val pitchDetector = YinPitchDetector(sampleRate)
    private val hopDurationMs = hopSize * 1000.0 / sampleRate

    /**
     * Toute la capture (lecture bloquante d'[AudioCaptureManager]) et le traitement
     * CPU (YIN, segmentation) tournent sur [Dispatchers.IO], jamais sur le thread
     * principal. Seule la collecte en aval (mise à jour du StateFlow de l'UI) reste
     * sur le dispatcher de l'appelant.
     */
    fun run(): Flow<LiveFrame> = flow {
        val segmenter = NoteSegmenter(frameDurationMs = hopDurationMs)
        var frameIndex = 0L
        var lastTimestampMs = 0L

        audioSource.captureAudio()
            .toAnalysisWindows(windowSize, hopSize)
            .collect { window ->
                val timestampMs = frameIndex * hopSize * 1000L / sampleRate
                lastTimestampMs = timestampMs

                val amplitude = rms(window)
                val pitch = pitchDetector.detectPitch(window)
                val completedNote = segmenter.processFrame(timestampMs, pitch, amplitude)

                emit(LiveFrame(timestampMs, pitch, amplitude, segmenter.currentTrackedNote, completedNote))
                frameIndex++
            }

        // Le flux de capture s'est terminé normalement (stop() a été appelé) :
        // on clôt une éventuelle note encore tenue.
        segmenter.finish(lastTimestampMs)?.let { finalNote ->
            emit(LiveFrame(lastTimestampMs, PitchResult.Unvoiced, 0f, null, finalNote))
        }
    }.flowOn(Dispatchers.IO)

    /** Arrête la capture ; [run] se termine proprement après avoir clos la dernière note. */
    fun stop() = audioSource.stop()

    private fun rms(window: FloatArray): Float {
        var sumOfSquares = 0.0
        for (sample in window) sumOfSquares += sample.toDouble() * sample
        return sqrt(sumOfSquares / window.size).toFloat()
    }

    companion object {
        // 2048 échantillons @44.1kHz (~46ms) : assez large pour résoudre les basses
        // fréquences vocales (YIN a besoin de fenêtre >= 2x la période la plus basse).
        const val WINDOW_SIZE = 2048
        // Recouvrement de 75% : ~11.6ms entre deux estimations, largement en dessous
        // des débounces du NoteSegmenter (60/120ms) pour une bonne réactivité.
        const val HOP_SIZE = 512
    }
}
