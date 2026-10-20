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
 *
 * Limite structurelle du modèle : rien n'empêche Viterbi de faire un très
 * bref détour par un état si quelques frames y correspondent mieux (ex: une
 * fenêtre d'analyse qui chevauche exactement une transition de pitch brutale
 * peut mesurer une fréquence transitoire proche d'une note tierce). C'est
 * exactement le problème que pYIN Notes documente et corrige par un élagage
 * des notes trop courtes en post-traitement — [minNoteDurationMs] fait la
 * même chose ici : toute note plus courte est fusionnée avec sa voisine.
 *
 * [scaleConstraint] restreint l'espace d'états aux notes de la gamme choisie
 * (comme la fonction "Key" des plugins voix->MIDI du marché) : moins de
 * notes candidates par octave, donc une note "fausse" est mécaniquement plus
 * loin de la bonne — moins d'ambiguïté, sans rien changer à l'algorithme.
 *
 * [correctLeadingOctaveErrors] corrige un artefact classique de l'attaque
 * vocale (avant que les cordes vocales ne se stabilisent, l'estimateur peut
 * accrocher une harmonique au lieu du fondamental, donnant une fréquence
 * environ une octave trop haute) : si la toute première note est brève et
 * strictement à une ou deux octaves de la note suivante, elle est recalée
 * sur la hauteur de cette dernière.
 */
class ViterbiNoteSegmenter(
    private val lowNote: Int = 33, // A1 — cohérent avec la plage du clavier affiché
    private val highNote: Int = 96, // C7
    private val pitchSigmaCents: Double = 35.0,
    private val selfTransitionLogProb: Double = ln(0.965),
    private val silenceSelfTransitionLogProb: Double = ln(0.9),
    private val toOrFromSilenceLogProb: Double = ln(0.02),
    private val minNoteDurationMs: Long = 60L,
    private val minAmplitudeForVelocity: Float = 0.01f,
    private val maxAmplitudeForVelocity: Float = 0.5f,
    private val scaleConstraint: ScaleConstraint = NoScaleConstraint,
    private val correctLeadingOctaveErrors: Boolean = true,
    private val leadingArtifactMaxDurationMs: Long = 350L,
    /**
     * Coût (log-probabilité, <= 0) payé une fois en *entrant* dans une note,
     * selon sa hauteur MIDI. Version douce de [scaleConstraint] : une note hors
     * gamme reste possible, mais un bref détour par elle devient plus cher.
     */
    private val noteEntryLogPrior: (Int) -> Double = { 0.0 }
) {
    private val noteStates = (lowNote..highNote).filter { scaleConstraint.contains(it) }
    private val entryLogPriors = DoubleArray(noteStates.size) { noteEntryLogPrior(noteStates[it]) }
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

        val prunedPath = pruneShortRuns(frames, path)
        val events = pathToNoteEvents(frames, prunedPath)
        return if (correctLeadingOctaveErrors) correctLeadingOctaveError(events) else events
    }

    /** Recale la toute première note sur la seconde si elle n'en est qu'un artefact d'octave bref. */
    private fun correctLeadingOctaveError(events: List<NoteEvent>): List<NoteEvent> {
        if (events.size < 2) return events
        val first = events[0]
        val second = events[1]
        if (first.durationMs > leadingArtifactMaxDurationMs) return events

        val semitoneDiff = first.midiNote - second.midiNote
        if (semitoneDiff != 12 && semitoneDiff != 24) return events

        return listOf(first.copy(midiNote = second.midiNote)) + events.drop(1)
    }

    /** Fusionne toute suite d'états plus courte que [minNoteDurationMs] avec l'état voisin. */
    private fun pruneShortRuns(frames: List<RawPitchFrame>, path: IntArray): IntArray {
        val result = path.copyOf()
        var changed = true
        while (changed) {
            changed = false
            var runStart = 0
            var t = 1
            while (t <= result.size) {
                if (t == result.size || result[t] != result[runStart]) {
                    val startMs = frames[runStart].timestampMs
                    val endMs = if (t < frames.size) frames[t].timestampMs else frames.last().timestampMs + 1
                    if (endMs - startMs < minNoteDurationMs) {
                        val replacement = if (runStart > 0) result[runStart - 1] else result.getOrElse(t) { result[runStart] }
                        for (k in runStart until t) result[k] = replacement
                        changed = true
                    }
                    runStart = t
                }
                t++
            }
        }
        return result
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
        if (to == silenceIndex) return toOrFromSilenceLogProb
        if (from == silenceIndex) return toOrFromSilenceLogProb + entryLogPriors[to - 1]
        val distance = abs(noteStates[from - 1] - noteStates[to - 1])
        return ln(0.03) - 0.4 * distance + entryLogPriors[to - 1]
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
