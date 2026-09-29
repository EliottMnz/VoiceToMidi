package com.example.voicetomidi.notes

import kotlin.math.abs
import kotlin.math.ln

/** Une mesure brute frame par frame, telle que produite par le pipeline temps réel. */
data class RawPitchFrame(
    val timestampMs: Long,
    val frequencyHz: Float,
    val confidence: Float,
    val rmsAmplitude: Float
)

/**
 * Post-traitement "hors-ligne" de la séquence complète de mesures de hauteur
 * en une liste de [NoteEvent] propre, via un modèle de Markov caché (HMM)
 * décodé par l'algorithme de Viterbi.
 *
 * Contrairement à [NoteSegmenter] (qui décide en temps réel, frame par frame,
 * avec des seuils réglés à la main), ce segmenteur trouve la séquence d'états
 * — silence ou une note par demi-ton — qui explique le mieux l'ENSEMBLE du
 * signal, en maximisant une probabilité globale plutôt qu'en appliquant des
 * règles locales :
 *  - l'émission d'un état note est une gaussienne en cents autour de sa
 *    fréquence exacte, pondérée par la confiance YIN de la frame (une frame
 *    peu fiable pèse moins dans la décision, comme dans pYIN) ;
 *  - la transition entre deux notes est d'autant moins probable qu'elles sont
 *    éloignées, et rester sur la même note est très probable.
 * Une frame aberrante isolée devient "chère" à expliquer par un changement
 * d'état (il faudrait payer deux transitions), donc Viterbi préfère
 * globalement l'ignorer — sans qu'aucune durée ou pourcentage n'ait été réglé
 * à la main pour ça.
 *
 * Comme Viterbi a besoin de voir toute la séquence pour décider, ce n'est
 * PAS utilisable en temps réel : on l'applique uniquement une fois
 * l'enregistrement terminé, sur l'historique complet des frames.
 */
class ViterbiNoteSegmenter(
    private val lowNote: Int = 33, // A1 — cohérent avec la plage du clavier affiché
    private val highNote: Int = 96, // C7
    private val pitchSigmaCents: Double = 35.0,
    private val selfTransitionLogProb: Double = ln(0.965),
    private val silenceSelfTransitionLogProb: Double = ln(0.9),
    private val toOrFromSilenceLogProb: Double = ln(0.02),
    private val minAmplitudeForVelocity: Float = 0.01f,
    private val maxAmplitudeForVelocity: Float = 0.5f
) {
    private val noteStates = (lowNote..highNote).toList()
    private val stateCount = noteStates.size + 1
    private val silenceIndex = 0

    fun segment(frames: List<RawPitchFrame>): List<NoteEvent> {
        if (frames.isEmpty()) return emptyList()

        var logDelta = DoubleArray(stateCount) { stateEmission(frames[0], it) }
        val backpointers = Array(frames.size) { IntArray(stateCount) }

        for (t in 1 until frames.size) {
            val frame = frames[t]
            val newLogDelta = DoubleArray(stateCount)
            for (s in 0 until stateCount) {
                var bestPrev = 0
                var bestScore = Double.NEGATIVE_INFINITY
                for (prev in 0 until stateCount) {
                    val score = logDelta[prev] + transitionLogProb(prev, s)
                    if (score > bestScore) {
                        bestScore = score
                        bestPrev = prev
                    }
                }
                newLogDelta[s] = bestScore + stateEmission(frame, s)
                backpointers[t][s] = bestPrev
            }
            logDelta = newLogDelta
        }

        var bestState = 0
        for (s in 1 until stateCount) {
            if (logDelta[s] > logDelta[bestState]) bestState = s
        }
        val path = IntArray(frames.size)
        path[frames.size - 1] = bestState
        for (t in frames.size - 2 downTo 0) {
            bestState = backpointers[t + 1][bestState]
            path[t] = bestState
        }

        return pathToNoteEvents(frames, path)
    }

    private fun stateEmission(frame: RawPitchFrame, state: Int): Double {
        val voiced = frame.confidence > 0f && frame.frequencyHz > 0f
        if (state == silenceIndex) {
            return if (voiced) -frame.confidence * 4.0 else 0.0
        }
        if (!voiced) return -1_000.0

        val note = noteStates[state - 1]
        val cents = centsBetween(frame.frequencyHz, note)
        val gaussianLogProb = -(cents * cents) / (2.0 * pitchSigmaCents * pitchSigmaCents)
        val weight = frame.confidence.toDouble().coerceIn(0.0, 1.0)
        return weight * gaussianLogProb
    }

    private fun transitionLogProb(from: Int, to: Int): Double {
        if (from == to) {
            return if (from == silenceIndex) silenceSelfTransitionLogProb else selfTransitionLogProb
        }
        if (from == silenceIndex || to == silenceIndex) {
            return toOrFromSilenceLogProb
        }
        val distance = abs(noteStates[from - 1] - noteStates[to - 1])
        return ln(0.03) - 0.4 * distance
    }

    private fun pathToNoteEvents(frames: List<RawPitchFrame>, path: IntArray): List<NoteEvent> {
        val events = mutableListOf<NoteEvent>()
        var runStartIndex = 0

        fun closeRun(endIndex: Int) {
            val state = path[runStartIndex]
            if (state == silenceIndex) return

            val note = noteStates[state - 1]
            val startMs = frames[runStartIndex].timestampMs
            val endMs = if (endIndex < frames.size) frames[endIndex].timestampMs else frames.last().timestampMs + 1
            val peakAmplitude = (runStartIndex until endIndex).maxOf { frames[it].rmsAmplitude }
            events += NoteEvent(
                midiNote = note,
                startTimeMs = startMs,
                endTimeMs = maxOf(endMs, startMs + 1),
                velocity = amplitudeToVelocity(peakAmplitude, minAmplitudeForVelocity, maxAmplitudeForVelocity)
            )
        }

        for (t in 1 until path.size) {
            if (path[t] != path[runStartIndex]) {
                closeRun(t)
                runStartIndex = t
            }
        }
        closeRun(path.size)

        return events
    }
}
