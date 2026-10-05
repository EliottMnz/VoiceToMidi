package com.example.voicetomidi.notes

import com.example.voicetomidi.pitch.PitchResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Convertit un flux de [PitchResult] (une estimation par fenêtre d'analyse) en
 * une séquence de [NoteEvent] discrets.
 *
 * La conversion Hz -> MIDI est un simple arrondi chromatique
 * (round(69 + 12*log2(f/440))), qui gère nativement la tolérance : toute
 * fréquence est associée à la note la plus proche. Toute la complexité est donc
 * dans la stabilisation temporelle, à deux niveaux :
 *  - un filtre médian glissant ([smoothingWindowMs]) absorbe le vibrato et le
 *    bruit d'estimation frame à frame sans jamais restreindre les notes
 *    possibles ;
 *  - une hystérésis à confirmation *adaptative* avant de valider un
 *    *changement* de note (alors qu'une note est déjà tenue) :
 *    1. zone morte en fréquence ([changeThresholdCents]) : la fréquence brute
 *       doit s'éloigner nettement de la note tenue (pas juste franchir la
 *       frontière à 50 cents), pour tolérer l'imprécision naturelle d'une
 *       voix qui n'est pas parfaitement juste ;
 *    2. au-delà de la zone morte, la durée de confirmation requise dépend de
 *       l'ampleur du saut : un écart proche d'un demi-ton (< [bigLeapThresholdCents],
 *       la zone ambiguë où imprécision et vrai changement se ressemblent) exige
 *       une confirmation longue et stricte ([minNoteChangeMs], [changeConfidenceRatio]
 *       sur toute la fenêtre de lissage) ; un saut large (>= [bigLeapThresholdCents],
 *       typique d'un arpège ou d'un intervalle de tierce et plus) est confirmé
 *       presque immédiatement ([fastChangeMs]), car un chanteur imprécis dérive
 *       très rarement aussi loin de la note tenue par accident. Sans cette
 *       distinction, une durée de confirmation unique empêcherait soit les
 *       mélodies rapides (si trop courte), soit resterait sourde à
 *       l'imprécision vocale (si trop longue) — les deux à la fois sont
 *       incompatibles avec un seuil de temps fixe.
 *    Sans zone morte, un flottement de voix assez long pour changer la note
 *    brute suffirait aussi à satisfaire un débounce de même durée, et ne
 *    serait donc jamais vraiment filtré.
 *  - un onset depuis le silence ([minOnsetMs]) reste volontairement plus court
 *    (réactivité au démarrage d'une note), et la fin de note ([minOffsetMs])
 *    utilise un débounce symétrique plus long pour absorber les micro-coupures
 *    de confiance (consonnes, souffle) sans couper la note prématurément.
 *
 * Cette classe est un processeur à état pur (pas de coroutines/IO) : on lui
 * pousse une frame à la fois via [processFrame], et on récupère un [NoteEvent]
 * chaque fois qu'une note se termine. [finish] clôt la note encore ouverte à
 * la fin du flux, s'il y en a une.
 */
class NoteSegmenter(
    frameDurationMs: Double,
    private val smoothingWindowMs: Double = 120.0,
    private val minOnsetMs: Double = 60.0,
    private val minNoteChangeMs: Double = 150.0,
    private val changeConfidenceRatio: Double = 0.65,
    private val changeThresholdCents: Double = 70.0,
    private val bigLeapThresholdCents: Double = 150.0,
    private val fastChangeMs: Double = 40.0,
    private val minOffsetMs: Double = 120.0,
    private val minConfidence: Float = 0.5f,
    private val minAmplitudeForVelocity: Float = 0.01f,
    private val maxAmplitudeForVelocity: Float = 0.5f,
    /** Restreint les notes détectées à cette gamme (ex: moins de notes candidates -> moins d'ambiguïté). */
    private val scaleConstraint: ScaleConstraint = NoScaleConstraint
) {

    private val smoothingWindowFrames = maxOf(1, (smoothingWindowMs / frameDurationMs).roundToInt())
    private val fastChangeWindowFrames = maxOf(1, (fastChangeMs / frameDurationMs).roundToInt())

    private sealed class State {
        object Silence : State()
        data class Tracking(val note: Int, val onsetTimeMs: Long, var peakAmplitude: Float) : State()
    }

    private var state: State = State.Silence
    private val rawNoteHistory = ArrayDeque<Int>()

    /** Note actuellement tenue et confirmée, ou null en silence / en attente de confirmation. */
    val currentTrackedNote: Int?
        get() = (state as? State.Tracking)?.note

    // Candidat en attente de confirmation : soit un onset (depuis le silence),
    // soit un changement de note (depuis une note déjà tenue).
    private var candidateNote: Int? = null
    private var candidateSinceMs: Long = 0L

    private var silenceSinceMs: Long? = null

    /** Traite une frame d'analyse ; retourne une [NoteEvent] si une note vient de se clore. */
    fun processFrame(timestampMs: Long, pitch: PitchResult, rmsAmplitude: Float): NoteEvent? {
        val voiced = pitch.confidence >= minConfidence && pitch.frequencyHz > 0f

        if (!voiced) {
            rawNoteHistory.clear()
            candidateNote = null
            return handleSilenceFrame(timestampMs)
        }

        silenceSinceMs = null

        val rawNote = scaleConstraint.nearestInScale(frequencyToMidiNote(pitch.frequencyHz))
        rawNoteHistory.addLast(rawNote)
        while (rawNoteHistory.size > smoothingWindowFrames) rawNoteHistory.removeFirst()
        val smoothedNote = median(rawNoteHistory)

        return when (val current = state) {
            is State.Silence -> {
                trackCandidate(smoothedNote, timestampMs)
                confirmOnsetIfStable(timestampMs, rmsAmplitude)
            }
            is State.Tracking -> {
                current.peakAmplitude = maxOf(current.peakAmplitude, rmsAmplitude)
                val distanceCents = abs(centsBetween(pitch.frequencyHz, current.note))

                if (rawNote == current.note || distanceCents < changeThresholdCents) {
                    // Toujours sur la note tenue, ou pas assez loin pour que ce soit
                    // autre chose qu'une imprécision vocale normale : on réinitialise
                    // le candidat (il devra repartir de zéro pour être reconsidéré).
                    candidateNote = null
                    null
                } else if (distanceCents >= bigLeapThresholdCents) {
                    // Saut large (arpège, intervalle de tierce et plus) : confirmation
                    // quasi immédiate sur une fenêtre de lissage réduite.
                    trackCandidate(rawNote, timestampMs)
                    confirmChangeIfStable(timestampMs, current, requiredMs = fastChangeMs, dominanceWindowFrames = fastChangeWindowFrames)
                } else {
                    // Écart ambigu, proche d'un demi-ton : confirmation lente et
                    // stricte sur toute la fenêtre de lissage.
                    trackCandidate(rawNote, timestampMs)
                    confirmChangeIfStable(timestampMs, current, requiredMs = minNoteChangeMs, dominanceWindowFrames = smoothingWindowFrames)
                }
            }
        }
    }

    /** À appeler une fois le flux terminé pour clore une éventuelle note encore tenue. */
    fun finish(finalTimestampMs: Long): NoteEvent? {
        val current = state
        if (current !is State.Tracking) return null
        state = State.Silence
        return buildNoteEvent(current, endTimeMs = finalTimestampMs)
    }

    private fun trackCandidate(smoothedNote: Int, timestampMs: Long) {
        if (candidateNote != smoothedNote) {
            candidateNote = smoothedNote
            candidateSinceMs = timestampMs
        }
    }

    private fun confirmOnsetIfStable(timestampMs: Long, rmsAmplitude: Float): NoteEvent? {
        val note = candidateNote ?: return null
        if (timestampMs - candidateSinceMs < minOnsetMs) return null
        state = State.Tracking(note = note, onsetTimeMs = candidateSinceMs, peakAmplitude = rmsAmplitude)
        candidateNote = null
        return null
    }

    private fun confirmChangeIfStable(
        timestampMs: Long,
        current: State.Tracking,
        requiredMs: Double,
        dominanceWindowFrames: Int
    ): NoteEvent? {
        val note = candidateNote ?: return null
        if (timestampMs - candidateSinceMs < requiredMs) return null
        if (dominance(note, dominanceWindowFrames) < changeConfidenceRatio) return null
        val closed = buildNoteEvent(current, endTimeMs = candidateSinceMs)
        state = State.Tracking(note = note, onsetTimeMs = candidateSinceMs, peakAmplitude = current.peakAmplitude)
        candidateNote = null
        return closed
    }

    private fun handleSilenceFrame(timestampMs: Long): NoteEvent? {
        val current = state
        if (current !is State.Tracking) return null

        val since = silenceSinceMs ?: timestampMs.also { silenceSinceMs = it }
        if (timestampMs - since < minOffsetMs) return null

        state = State.Silence
        silenceSinceMs = null
        return buildNoteEvent(current, endTimeMs = since)
    }

    private fun buildNoteEvent(tracking: State.Tracking, endTimeMs: Long): NoteEvent {
        val safeEnd = maxOf(endTimeMs, tracking.onsetTimeMs + 1)
        return NoteEvent(
            midiNote = tracking.note,
            startTimeMs = tracking.onsetTimeMs,
            endTimeMs = safeEnd,
            velocity = amplitudeToVelocity(tracking.peakAmplitude, minAmplitudeForVelocity, maxAmplitudeForVelocity)
        )
    }

    private fun median(values: Collection<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** Fraction des [windowFrames] dernières frames occupée par [note]. */
    private fun dominance(note: Int, windowFrames: Int): Double {
        if (rawNoteHistory.isEmpty()) return 0.0
        val window = rawNoteHistory.takeLast(windowFrames)
        return window.count { it == note }.toDouble() / window.size
    }
}
