package com.example.voicetomidi.notes

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Volume (RMS) minimal d'une voix, ~-50 dBFS : une voix au micro d'un téléphone est
 * bien au-dessus (-35 à -10 dBFS), un bruit de pièce ou le micro inactif de
 * l'émulateur (~-75 dBFS) bien en dessous — YIN y trouverait pourtant des "notes".
 */
const val MIN_VOICE_RMS = 0.003f

/**
 * Interrupteurs des étapes de [MelodyAnalyzer], pour mesurer l'apport de chacune
 * (ablation "une étape enlevée à la fois" dans le benchmark). L'appli utilise
 * toujours la valeur par défaut : tout activé.
 */
data class AnalysisSteps(
    val relativeNoiseGate: Boolean = true,
    val tuning: Boolean = true,
    val keyAwareSecondPass: Boolean = true,
    val mergeAndHesitations: Boolean = true,
    val energySplit: Boolean = true,
    val keySnapWithTaps: Boolean = true
) {
    companion object {
        /**
         * Réglage par défaut de l'appli : garder ce qui a été chanté. Mesuré sur
         * vocadito (40 vraies voix, 30 de réglage + 10 jamais vues) : le
         * calibrage de justesse et l'arbitrage par la gamme en mode bouton
         * coûtaient 3 à 5 points (bouton : 0,69 -> 0,75 sur les extraits jamais
         * vus en les retirant). Vocadito note ce qui est chanté, pas l'intention :
         * ces corrections restent disponibles via [CORRECTED].
         */
        val AS_SUNG = AnalysisSteps(tuning = false, keySnapWithTaps = false)

        /** Option "Rendre plus juste" : pour une voix qui tombe entre deux notes ou qui dérive. */
        val CORRECTED = AnalysisSteps()
    }
}

/** Intervalle pendant lequel l'utilisateur a maintenu le bouton "note" (timeline audio, en ms). */
data class TapSegment(val startMs: Long, val endMs: Long)

/** Résultat de l'analyse d'une prise complète. */
data class MelodyAnalysis(
    val notes: List<NoteEvent>,
    /** Tonalité la plus probable, ou null si aucune note n'a été trouvée. */
    val key: DetectedKey?,
    /**
     * Frames recalées pour le pitch bend : chaque note est centrée sur sa
     * hauteur finale, il ne reste que le vibrato autour (pas l'erreur de
     * justesse corrigée, sinon le pitch bend la réintroduirait à l'export).
     */
    val vibratoFrames: List<RawPitchFrame>,
    /** Vrai si rien dans la prise n'atteint le volume d'une voix (micro coupé, trop loin...). */
    val inputTooQuiet: Boolean = false
)

/** Une note avant décision finale : bornes, hauteur continue mesurée et stabilité. */
internal data class NoteEstimate(
    val startMs: Long,
    val endMs: Long,
    /** Hauteur centrale en demi-tons MIDI (non arrondie), après calibrage de justesse. */
    val semitones: Double,
    /** Écart médian de la voix autour de [semitones] pendant la note, en cents. */
    val spreadCents: Double,
    val velocity: Int
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Transforme une prise brute (frames YIN) en notes propres, sans que
 * l'utilisateur ait à connaître quoi que ce soit en musique :
 *
 * 1. **Calibrage de justesse** ([TuningEstimator]) : compense le décalage
 *    global du chanteur et sa dérive lente.
 * 2. **Découpage en notes** : si l'utilisateur a maintenu le bouton pendant
 *    ses notes ([TapSegment]), ses appuis donnent les bornes, recalées sur les
 *    vrais débuts/fins de son à ±[tapSnapWindowMs]. Sinon, Viterbi trouve le
 *    découpage le plus probable.
 * 3. **Une hauteur par note** : médiane pondérée du *cœur* de la note (les
 *    bords, où la voix attaque et glisse, sont ignorés).
 * 4. **Tonalité** ([KeyDetector]) : probabilité de chaque tonalité au vu des
 *    notes, pondérées par leur durée.
 * 5. **La gamme comme arbitre** : chaque note choisit le demi-ton qui
 *    maximise `vraisemblance(hauteur chantée) × probabilité a priori (gamme)`.
 *    Le poids de la gamme est volontairement faible : une note chantée
 *    clairement reste telle quelle, même hors gamme ; seules les notes qui
 *    tombent entre deux demi-tons sont tranchées en faveur de la gamme.
 *    Mesuré sur de vraies voix (benchmark vocadito, 40 extraits annotés par
 *    des musiciens) : forcer les notes dans la gamme faisait passer le score
 *    de 0,63 à 0,54 — les notes "hors gamme" chantées franchement sont bien
 *    plus souvent voulues (ornements, modes, chromatismes) que des erreurs.
 *    En mode automatique, cet arbitrage est fait par le 2e passage Viterbi ;
 *    en mode bouton, note par note ([snap]).
 * 6. **Deuxième passage** (mode automatique) : Viterbi est relancé en sachant
 *    la tonalité, les morceaux voisins tombés sur la même note sont recollés,
 *    et les hésitations ("doooo do# doo") sont absorbées dans la note tenue.
 *    En mode bouton, rien de tout ça : c'est l'utilisateur qui a dit où
 *    étaient ses notes.
 */
class MelodyAnalyzer(
    private val minConfidence: Float = 0.5f,
    private val tapSnapWindowMs: Long = 150L,
    /** Trou de voisement plus court que ça = consonne/souffle, pas une vraie fin de note. */
    private val voicingGapFillMs: Long = 60L,
    /**
     * Poids a priori d'une note hors gamme quand la tonalité est certaine.
     * Avec [pitchSigmaCents] = 25, la frontière entre une note hors gamme et
     * sa voisine dans la gamme passe de 50 à ~42 cents : seules les notes
     * vraiment ambiguës basculent (voir la doc de la classe pour la mesure).
     */
    private val outOfKeyWeight: Double = 0.3,
    /** Incertitude sur la hauteur centrale d'une note, en cents. */
    private val pitchSigmaCents: Double = 25.0,
    /** Fraction du coût hors gamme appliquée au découpage Viterbi du 2e passage. */
    private val viterbiKeyPriorScale: Double = 0.5,
    private val mergeGapMs: Long = 30L,
    private val hesitationMaxMs: Long = 150L,
    private val hesitationMaxSemitones: Int = 2,
    /** Creux de volume (fraction des pics de part et d'autre) qui sépare deux notes répétées. */
    private val energyDipRatio: Float = 0.35f,
    private val energySplitMinPartMs: Long = 80L,
    private val leadingArtifactMaxDurationMs: Long = 350L,
    /** Volume minimal d'une voix (RMS), en dessous duquel la prise est considérée muette. */
    private val minVoiceRms: Float = MIN_VOICE_RMS,
    /**
     * Fraction du volume de la voix ([LOUD_PERCENTILE] des frames voisées) sous
     * laquelle une frame est du souffle ou du bruit de fond, pas une note.
     */
    private val relativeGateRatio: Float = 0.06f,
    private val steps: AnalysisSteps = AnalysisSteps.AS_SUNG,
    internal val tapShift: Boolean = true,
    internal val tapSearchAfterMs: Long = 30L,
    internal val minTapShiftMs: Long = 130L,
    internal val minTapNoteMs: Long = MIN_TAP_NOTE_MS
) {

    fun analyze(frames: List<RawPitchFrame>, taps: List<TapSegment> = emptyList()): MelodyAnalysis {
        if (frames.isEmpty()) return MelodyAnalysis(emptyList(), null, frames)

        val voicedRms = frames.filter { it.frequencyHz > 0f && it.confidence > 0f }.map { it.rmsAmplitude }.sorted()
        val loudLevel = voicedRms.getOrNull((voicedRms.size * LOUD_PERCENTILE).toInt()) ?: 0f
        if (loudLevel < minVoiceRms) return MelodyAnalysis(emptyList(), null, frames, inputTooQuiet = true)
        val gated = if (steps.relativeNoiseGate) gateQuietFrames(frames, loudLevel * relativeGateRatio) else frames

        val corrected = if (steps.tuning) applyTuningOffsets(gated, TuningEstimator(minConfidence = minConfidence).estimate(gated)) else gated

        val finalEstimates: List<NoteEstimate>
        val finalNotes: List<Int>
        val keys: KeyDistribution?

        if (taps.isNotEmpty()) {
            val estimates = correctLeadingOctaveError(
                refineTaps(taps, corrected)
                    .filter { (start, end) -> end - start >= minTapNoteMs }
                    .mapNotNull { (start, end) -> estimate(corrected, start, end, hint = null) }
            )
            keys = if (estimates.isEmpty()) null else KeyDetector.detect(estimates.map(::weighted))
            finalEstimates = estimates
            finalNotes = when {
                keys == null -> emptyList()
                steps.keySnapWithTaps -> snap(estimates, keys)
                else -> estimates.map { it.semitones.roundToInt() }
            }
        } else {
            val firstPass = estimatesFromViterbi(corrected, ViterbiNoteSegmenter())
            if (firstPass.isEmpty()) {
                keys = null
                finalEstimates = emptyList()
                finalNotes = emptyList()
            } else {
                val firstKeys = KeyDetector.detect(firstPass.map(::weighted))
                val secondPass = if (!steps.keyAwareSecondPass) firstPass else estimatesFromViterbi(
                    corrected,
                    ViterbiNoteSegmenter(noteEntryLogPrior = { note ->
                        viterbiKeyPriorScale * ln(firstKeys.pitchClassPrior(note.mod(12), outOfKeyWeight))
                    })
                ).ifEmpty { firstPass }
                keys = KeyDetector.detect(secondPass.map(::weighted))
                // Le 2e passage Viterbi a déjà tenu compte de la gamme : son demi-ton est gardé tel
                // quel (re-corriger chaque note derrière lui coûtait 2 points sur vocadito).
                val viterbiNotes = secondPass.map { it.semitones.roundToInt() }
                val cleaned = secondPass.zip(viterbiNotes).let { if (steps.mergeAndHesitations) absorbHesitations(mergeSameNoteNeighbors(it)) else it }
                val merged = if (steps.energySplit) splitOnEnergyDips(cleaned, corrected) else cleaned
                finalEstimates = merged.map { it.first }
                finalNotes = merged.map { it.second }
            }
        }

        val notes = finalEstimates.indices.map { i ->
            val e = finalEstimates[i]
            NoteEvent(midiNote = finalNotes[i], startTimeMs = e.startMs, endTimeMs = e.endMs, velocity = e.velocity)
        }
        return MelodyAnalysis(
            notes = notes,
            key = keys?.best,
            vibratoFrames = centerFramesOnNotes(corrected, finalEstimates, finalNotes)
        )
    }

    /** Les frames trop faibles par rapport à la voix deviennent du silence. */
    private fun gateQuietFrames(frames: List<RawPitchFrame>, threshold: Float): List<RawPitchFrame> =
        frames.map { if (it.rmsAmplitude < threshold && it.frequencyHz > 0f) it.copy(frequencyHz = 0f, confidence = 0f) else it }

    internal fun weighted(e: NoteEstimate) =
        WeightedPitch(e.semitones, (e.durationMs / 300.0).coerceIn(0.5, 2.0))

    /**
     * Viterbi choisit le demi-ton (il voit toute la note, transitions comprises),
     * la médiane ne fait que dire *où* dans ce demi-ton se trouve la voix — d'où
     * le bornage à ±0.49 : mesuré sur vocadito, laisser la médiane contredire
     * Viterbi coûtait des notes justes.
     */
    internal fun estimatesFromViterbi(frames: List<RawPitchFrame>, segmenter: ViterbiNoteSegmenter): List<NoteEstimate> =
        segmenter.segment(frames).map { event ->
            val estimate = estimate(frames, event.startTimeMs, event.endTimeMs, hint = event.midiNote, velocity = event.velocity)
                ?: NoteEstimate(event.startTimeMs, event.endTimeMs, event.midiNote.toDouble(), 50.0, event.velocity)
            estimate.copy(semitones = estimate.semitones.coerceIn(event.midiNote - 0.49, event.midiNote + 0.49))
        }

    // --- Étape 2 : recalage des appuis sur le son -------------------------------------------

    /**
     * Recale chaque appui sur le vrai début/la vraie fin de la note :
     * 1. un début ou une fin de son (silence -> voix) à ±[tapSnapWindowMs] ;
     * 2. à défaut (chant lié, sans silence entre les notes), le moment où la
     *    hauteur change franchement, à ±[tapSnapWindowMs] de l'appui.
     * Une note se termine au plus tard quand la suivante commence.
     */
    internal fun refineTaps(taps: List<TapSegment>, frames: List<RawPitchFrame>): List<Pair<Long, Long>> {
        val voiced = fillShortGaps(frames.map { it.confidence >= minConfidence && it.frequencyHz > 0f }, frames)
        val onsets = mutableListOf<Long>()
        val offsets = mutableListOf<Long>()
        for (i in frames.indices) {
            if (!voiced[i]) continue
            if (i == 0 || !voiced[i - 1]) onsets += frames[i].timestampMs
            if (i == frames.lastIndex || !voiced[i + 1]) offsets += frames.getOrNull(i + 1)?.timestampMs ?: (frames[i].timestampMs + 1)
        }
        val semitones = frames.map { if (it.confidence >= minConfidence && it.frequencyHz > 0f) toSemitones(it.frequencyHz) else Double.NaN }

        // Décalage systématique entre les appuis et la voix, mesuré sur la prise
        // elle-même : un doigt (ou un appareil) en retard ou en avance de façon
        // constante est recalé d'un bloc, avant le recalage fin note par note.
        val shift = if (!tapShift) 0L else systematicTapOffset(taps, (onsets + pitchChangePoints(frames, semitones)).sorted())
        val sorted = taps.sortedBy { it.startMs }.map { TapSegment(it.startMs + shift, it.endMs + shift) }
        val starts = sorted.map { tap ->
            nearestWithin(onsets, tap.startMs) ?: pitchChangeNear(frames, semitones, tap.startMs) ?: tap.startMs
        }
        return sorted.indices.map { k ->
            val start = maxOf(starts[k], if (k > 0) starts[k - 1] + 1 else Long.MIN_VALUE)
            var end = nearestWithin(offsets, sorted[k].endMs) ?: sorted[k].endMs
            if (k + 1 < starts.size) end = minOf(end, starts[k + 1])
            if (end <= start) end = start + 1
            start to end
        }
    }

    /**
     * Décalage systématique entre les appuis et la voix (début de son ou
     * changement de note), à ±[MAX_TAP_SHIFT_MS]. Mesuré sur une vraie tablette :
     * appuis ~250 ms en avance sur la voix, de façon régulière ; ailleurs, le
     * doigt est plutôt en retard. Nul s'il y a trop peu d'appuis, ou si aucun
     * décalage n'aligne nettement mieux les appuis que l'absence de décalage.
     */
    private fun systematicTapOffset(taps: List<TapSegment>, events: List<Long>): Long {
        if (taps.size < 3 || events.isEmpty()) return 0L
        // Corrélation : pour chaque décalage candidat, combien d'appuis tombent sur un
        // événement de voix ? On garde le meilleur (le plus petit en cas d'égalité).
        // Plus robuste qu'une médiane d'écarts au plus proche : avec des notes courtes,
        // l'événement le plus proche d'un appui en avance est souvent la note d'avant.
        fun hits(shift: Long) = taps.count { tap ->
            val t = tap.startMs + shift
            val i = events.binarySearch(t).let { if (it >= 0) it else -it - 1 }
            listOfNotNull(events.getOrNull(i), events.getOrNull(i - 1)).any { abs(it - t) <= TAP_SHIFT_TOLERANCE_MS }
        }
        val candidates = (-MAX_TAP_SHIFT_MS..MAX_TAP_SHIFT_MS step TAP_SHIFT_STEP_MS).sortedBy { abs(it) }
        val best = candidates.maxBy { hits(it) }
        return if (hits(best) > hits(0L) + taps.size / 10 && abs(best) >= minTapShiftMs) best else 0L
    }

    /** Instants où la hauteur change franchement (maximum local du saut, ≥ [MIN_PITCH_CHANGE_SEMITONES]). */
    private fun pitchChangePoints(frames: List<RawPitchFrame>, semitones: List<Double>): List<Long> {
        val jumps = DoubleArray(frames.size) { i -> pitchJumpAt(semitones, i) }
        return frames.indices.filter { i ->
            jumps[i] >= MIN_PITCH_CHANGE_SEMITONES &&
                (maxOf(0, i - 3)..minOf(frames.lastIndex, i + 3)).all { jumps[it] <= jumps[i] }
        }.map { frames[it].timestampMs }
    }

    /** Écart entre la hauteur médiane des ~50 ms après et des ~50 ms avant la frame [i]. */
    private fun pitchJumpAt(semitones: List<Double>, i: Int): Double {
        val before = (maxOf(0, i - 4) until i).map { semitones[it] }.filterNot { it.isNaN() }
        val after = (i until minOf(semitones.size, i + 4)).map { semitones[it] }.filterNot { it.isNaN() }
        if (before.size < 2 || after.size < 2) return 0.0
        return abs(after.sorted()[after.size / 2] - before.sorted()[before.size / 2])
    }

    /**
     * Instant, à ±[tapSnapWindowMs] de l'appui, où la hauteur médiane des
     * ~50 ms suivantes diffère le plus des ~50 ms précédentes — si l'écart
     * dépasse un demi-ton bien marqué (après correction du décalage
     * systématique, l'erreur restante peut aller dans les deux sens).
     */
    private fun pitchChangeNear(frames: List<RawPitchFrame>, semitones: List<Double>, tapStartMs: Long): Long? {
        var bestJump = MIN_PITCH_CHANGE_SEMITONES
        var bestTime: Long? = null
        for (i in frames.indices) {
            val t = frames[i].timestampMs
            if (t < tapStartMs - tapSnapWindowMs || t > tapStartMs + tapSearchAfterMs) continue
            val jump = pitchJumpAt(semitones, i)
            if (jump > bestJump) {
                bestJump = jump
                bestTime = t
            }
        }
        return bestTime
    }

    private fun nearestWithin(candidates: List<Long>, target: Long): Long? =
        candidates.filter { abs(it - target) <= tapSnapWindowMs }.minByOrNull { abs(it - target) }

    private fun fillShortGaps(voiced: List<Boolean>, frames: List<RawPitchFrame>): BooleanArray {
        val result = voiced.toBooleanArray()
        var i = 0
        while (i < result.size) {
            if (result[i]) { i++; continue }
            val gapStart = i
            while (i < result.size && !result[i]) i++
            val bounded = gapStart > 0 && i < result.size
            if (bounded && frames[i].timestampMs - frames[gapStart].timestampMs <= voicingGapFillMs) {
                for (k in gapStart until i) result[k] = true
            }
        }
        return result
    }

    // --- Étape 3 : une hauteur par note ------------------------------------------------------

    /**
     * Hauteur centrale d'un segment. Avec [hint] (note choisie par Viterbi), on
     * ne garde que les frames proches de cette note : Viterbi a déjà tranché le
     * voisinage, on affine seulement. Sans [hint] (appui bouton), les erreurs
     * d'octave isolées sont écartées autour d'une première médiane.
     */
    internal fun estimate(
        frames: List<RawPitchFrame>,
        startMs: Long,
        endMs: Long,
        hint: Int?,
        velocity: Int? = null
    ): NoteEstimate? {
        val inRange = frames.filter { it.timestampMs in startMs until endMs && it.frequencyHz > 0f && it.confidence > 0f }
        val reliable = inRange.filter { it.confidence >= minConfidence }.ifEmpty { inRange }
        if (reliable.isEmpty()) return null

        val trimMs = minOf((endMs - startMs) * 0.2, 80.0).toLong()
        val core = reliable.filter { it.timestampMs >= startMs + trimMs && it.timestampMs < endMs - trimMs }
            .takeIf { it.size >= 3 } ?: reliable

        var points = core.map { toSemitones(it.frequencyHz) to it.confidence.toDouble() }
        if (hint != null) {
            points = points.filter { abs(it.first - hint) <= 1.5 }
            if (points.isEmpty()) return null
        } else {
            val roughCenter = weightedMedian(points)
            points = points.filter { abs(it.first - roughCenter) <= 6.0 }
        }

        val center = weightedMedian(points)
        val spread = weightedMedian(points.map { abs(it.first - center) to it.second }) * 100.0
        val peakRms = inRange.maxOf { it.rmsAmplitude }
        return NoteEstimate(startMs, endMs, center, spread, velocity ?: amplitudeToVelocity(peakRms))
    }

    /** Même correction que [ViterbiNoteSegmenter] pour l'attaque : 1re note brève à une/deux octaves de la 2e. */
    private fun correctLeadingOctaveError(estimates: List<NoteEstimate>): List<NoteEstimate> {
        if (estimates.size < 2) return estimates
        val first = estimates[0]
        if (first.durationMs > leadingArtifactMaxDurationMs) return estimates
        val diff = (first.semitones - estimates[1].semitones).roundToInt()
        if (diff != 12 && diff != 24) return estimates
        return listOf(first.copy(semitones = first.semitones - diff)) + estimates.drop(1)
    }

    // --- Étape 5 : correction douce vers la gamme --------------------------------------------

    /** Choisit le demi-ton final de chaque note (voir la doc de la classe). */
    internal fun snap(estimates: List<NoteEstimate>, keys: KeyDistribution): List<Int> {
        return estimates.map { e ->
            // Une note très instable (voix qui tremble beaucoup) a un centre moins sûr.
            var sigma = maxOf(pitchSigmaCents, e.spreadCents).coerceAtMost(70.0)
            if (e.durationMs < 150) sigma *= 1.3
            val nearest = e.semitones.roundToInt()
            (nearest - 2..nearest + 2).maxBy { candidate ->
                val cents = (e.semitones - candidate) * 100.0
                -(cents * cents) / (2 * sigma * sigma) + ln(keys.pitchClassPrior(candidate.mod(12), outOfKeyWeight))
            }
        }
    }

    // --- Étape 6 : recollage et notes répétées ------------------------------------------------

    /**
     * "Do Do Do" chanté avec de simples consonnes entre les notes : la hauteur
     * ne bouge pas, et la fenêtre d'analyse (46 ms) déborde sur des coupures
     * aussi courtes — le seul indice est le creux de volume. Une note est
     * coupée là où le volume tombe sous [energyDipRatio] fois le plus faible
     * des deux pics qui l'entourent, si chaque morceau dure au moins
     * [energySplitMinPartMs].
     */
    internal fun splitOnEnergyDips(notes: List<Pair<NoteEstimate, Int>>, frames: List<RawPitchFrame>): List<Pair<NoteEstimate, Int>> =
        notes.flatMap { (estimate, note) ->
            splitPoints(estimate, frames).let { cuts ->
                val bounds = listOf(estimate.startMs) + cuts + listOf(estimate.endMs)
                bounds.zipWithNext { start, end -> estimate.copy(startMs = start, endMs = end) to note }
            }
        }

    private fun splitPoints(estimate: NoteEstimate, frames: List<RawPitchFrame>): List<Long> {
        if (estimate.durationMs < 2 * energySplitMinPartMs) return emptyList()
        val inNote = frames.filter { it.timestampMs >= estimate.startMs && it.timestampMs < estimate.endMs }
        if (inNote.size < 5) return emptyList()
        val rms = FloatArray(inNote.size) { i ->
            val lo = maxOf(0, i - 1)
            val hi = minOf(inNote.lastIndex, i + 1)
            (lo..hi).map { inNote[it].rmsAmplitude }.average().toFloat()
        }

        val candidates = mutableListOf<Pair<Long, Float>>()
        for (i in inNote.indices) {
            val t = inNote[i].timestampMs
            if (t - estimate.startMs < energySplitMinPartMs || estimate.endMs - t < energySplitMinPartMs) continue
            val isLocalMin = (maxOf(0, i - 2)..minOf(inNote.lastIndex, i + 2)).all { rms[it] >= rms[i] }
            if (!isLocalMin) continue
            val peakBefore = (0 until i).maxOf { rms[it] }
            val peakAfter = (i + 1 until inNote.size).maxOf { rms[it] }
            val depth = rms[i] / minOf(peakBefore, peakAfter).coerceAtLeast(1e-6f)
            if (depth < energyDipRatio) candidates += t to depth
        }

        val cuts = mutableListOf<Long>()
        for ((t, _) in candidates.sortedBy { it.second }) {
            if (cuts.all { abs(it - t) >= energySplitMinPartMs }) cuts += t
        }
        return cuts.sorted()
    }

    /** Recolle deux notes contiguës tombées sur le même demi-ton (une hésitation coupée en deux). */
    internal fun mergeSameNoteNeighbors(notes: List<Pair<NoteEstimate, Int>>): List<Pair<NoteEstimate, Int>> {
        val result = mutableListOf<Pair<NoteEstimate, Int>>()
        for (current in notes) {
            val last = result.lastOrNull()
            if (last != null && last.second == current.second && contiguous(last.first, current.first)) {
                result[result.lastIndex] = combine(last.first, current.first) to last.second
            } else {
                result += current
            }
        }
        return result
    }

    /**
     * "doooo do# doo" : une note brève, proche (≤ [hesitationMaxSemitones]),
     * collée entre deux morceaux de la *même* note nettement plus longs, est
     * une hésitation de la voix, pas une note voulue — elle est absorbée.
     * Une vraie broderie rapide ("mi fa mi" en notes égales) n'est pas touchée :
     * la note du milieu n'y est pas beaucoup plus courte que ses voisines.
     */
    internal fun absorbHesitations(notes: List<Pair<NoteEstimate, Int>>): List<Pair<NoteEstimate, Int>> {
        var current = notes
        while (true) {
            val i = (1 until current.size - 1).firstOrNull { k ->
                val (before, beforeNote) = current[k - 1]
                val (middle, middleNote) = current[k]
                val (after, afterNote) = current[k + 1]
                beforeNote == afterNote &&
                    abs(middleNote - beforeNote) <= hesitationMaxSemitones &&
                    middle.durationMs < hesitationMaxMs &&
                    middle.durationMs * 2 < minOf(before.durationMs, after.durationMs) &&
                    contiguous(before, middle) && contiguous(middle, after)
            } ?: return current
            val (before, note) = current[i - 1]
            val absorbed = before.copy(endMs = current[i].first.endMs)
            current = current.take(i - 1) + listOf(combine(absorbed, current[i + 1].first) to note) + current.drop(i + 2)
        }
    }

    private fun contiguous(first: NoteEstimate, second: NoteEstimate) = second.startMs - first.endMs <= mergeGapMs

    private fun combine(first: NoteEstimate, second: NoteEstimate): NoteEstimate {
        val totalMs = (first.durationMs + second.durationMs).toDouble().coerceAtLeast(1.0)
        return first.copy(
            endMs = second.endMs,
            semitones = (first.semitones * first.durationMs + second.semitones * second.durationMs) / totalMs,
            spreadCents = maxOf(first.spreadCents, second.spreadCents),
            velocity = maxOf(first.velocity, second.velocity)
        )
    }

    private fun centerFramesOnNotes(
        frames: List<RawPitchFrame>,
        estimates: List<NoteEstimate>,
        notes: List<Int>
    ): List<RawPitchFrame> {
        if (estimates.isEmpty()) return frames
        return frames.map { frame ->
            if (frame.frequencyHz <= 0f) return@map frame
            val index = estimates.indexOfFirst { frame.timestampMs >= it.startMs && frame.timestampMs < it.endMs }
            if (index < 0) return@map frame
            val shift = notes[index] - estimates[index].semitones
            frame.copy(frequencyHz = (frame.frequencyHz * 2.0.pow(shift / 12.0)).toFloat())
        }
    }
}

private const val MIN_PITCH_CHANGE_SEMITONES = 0.7

/** Décalage systématique maximal cherché entre les appuis et la voix. */
private const val MAX_TAP_SHIFT_MS = 400L
private const val TAP_SHIFT_STEP_MS = 10L
private const val TAP_SHIFT_TOLERANCE_MS = 40L

/** Centile du volume des frames voisées pris comme "volume de la voix" (robuste à quelques pics). */
private const val LOUD_PERCENTILE = 0.9

/** Une note de moins de 40 ms après recalage n'est pas une note chantée (appui mal placé). */
private const val MIN_TAP_NOTE_MS = 40L

private fun toSemitones(frequencyHz: Float): Double = 69 + 12 * log2(frequencyHz / 440.0)

/** Médiane pondérée de paires (valeur, poids). */
private fun weightedMedian(points: List<Pair<Double, Double>>): Double {
    val sorted = points.sortedBy { it.first }
    val total = sorted.sumOf { it.second }
    if (total <= 0.0) return sorted[sorted.size / 2].first
    var cumulative = 0.0
    for ((value, weight) in sorted) {
        cumulative += weight
        if (cumulative >= total / 2) return value
    }
    return sorted.last().first
}
