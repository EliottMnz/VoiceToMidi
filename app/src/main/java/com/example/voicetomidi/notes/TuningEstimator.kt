package com.example.voicetomidi.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mesure de combien la personne chante "à côté" du diapason standard
 * (La4 = 440 Hz), et comment ce décalage évolue au fil de la prise.
 *
 * Quelqu'un qui chante tout 40 cents trop bas n'est pas "faux" au sens
 * mélodique : ses intervalles sont justes, c'est son diapason intérieur qui
 * est décalé. Sans compensation, chaque note tombe pile entre deux demi-tons
 * et l'arrondi oscille au hasard. De même, un chanteur débutant dérive souvent
 * lentement (il commence en Do et finit presque en Do#).
 *
 * Modèle : décalage(t) = a + b·t (un décalage de départ et une vitesse de
 * dérive). Pour chaque frame, on ne garde que l'écart au demi-ton le plus
 * proche (entre -50 et +50 cents). Ces écarts sont circulaires (+50 et -50
 * sont le même point) : pour chaque vitesse b candidate, le meilleur a est
 * une moyenne *circulaire*, et on retient le couple qui aligne le mieux toutes
 * les frames sur la grille des demi-tons. Pondération par la confiance YIN et
 * par la stabilité de la hauteur (un glissé entre deux notes ne dit rien du
 * diapason).
 *
 * Pourquoi une droite et pas un suivi local (fenêtre glissante) : une fenêtre
 * courte "avale" les erreurs aléatoires de chaque note (elle croit que le
 * chanteur est juste, et la correction de gamme devient trop timide), et une
 * fenêtre longue est en retard sur la dérive aux extrémités de la prise. Une
 * dérive réelle est lente et régulière : deux paramètres suffisent, et
 * l'erreur propre à chaque note ne peut pas être absorbée.
 */
class TuningEstimator(
    private val minConfidence: Float = 0.5f,
    private val smoothingHalfWindowMs: Long = 100L,
    /** Vitesse de dérive maximale envisagée, en cents par seconde (un demi-ton en ~12 s). */
    private val maxDriftCentsPerSecond: Double = 8.0,
    private val driftStepCentsPerSecond: Double = 0.25,
    /** Pénalité par cent/s de dérive : sans vraie amélioration, on préfère "pas de dérive". */
    private val driftPenalty: Double = 0.02,
    /**
     * Une dérive n'est retenue que si, une fois compensée, les notes s'alignent
     * vraiment bien sur la grille ([minDriftConcentration]) et nettement mieux
     * que sans dérive ([minDriftGain]). Sur une voix très dispersée, une
     * "dérive" ajustée sur le bruit améliore toujours un peu le score.
     */
    private val minDriftConcentration: Double = 0.6,
    private val minDriftGain: Double = 0.3,
    /** Pente (cents par frame) au-delà de laquelle une frame est considérée comme un glissé. */
    private val stableSlopeCents: Double = 15.0,
    /** Concentration minimale des écarts pour faire confiance à l'estimation. */
    private val minConcentration: Double = 0.2
) {

    /** Décalage estimé, en cents, pour chaque frame de [frames] (même indice). */
    fun estimate(frames: List<RawPitchFrame>): DoubleArray {
        val n = frames.size
        if (n == 0) return DoubleArray(0)

        val raw = DoubleArray(n) { i ->
            val f = frames[i]
            if (f.confidence >= minConfidence && f.frequencyHz > 0f) 69 + 12 * log2(f.frequencyHz / 440.0) else Double.NaN
        }
        val semitones = smooth(frames, raw)
        val weights = DoubleArray(n) { i -> frameWeight(frames, semitones, i) }
        val startMs = frames.first().timestampMs
        val seconds = DoubleArray(n) { (frames[it].timestampMs - startMs) / 1000.0 }
        val deviationsCents = DoubleArray(n) { i ->
            if (weights[i] > 0.0) (semitones[i] - semitones[i].roundToInt()) * 100.0 else 0.0
        }
        if (weights.sum() < 1e-6) return DoubleArray(n)

        val noDrift = fit(deviationsCents, weights, seconds, drift = 0.0)
        var best = noDrift
        var bestScore = noDrift.concentration
        val steps = (maxDriftCentsPerSecond / driftStepCentsPerSecond).roundToInt()
        for (step in -steps..steps) {
            if (step == 0) continue
            val drift = step * driftStepCentsPerSecond
            val candidate = fit(deviationsCents, weights, seconds, drift)
            val score = candidate.concentration - driftPenalty * abs(drift)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        if (best.concentration < minDriftConcentration || best.concentration - noDrift.concentration < minDriftGain) {
            best = noDrift
        }
        // Aucun décalage net (voix trop dispersée) : mieux vaut ne rien corriger qu'inventer.
        if (best.concentration < minConcentration) return DoubleArray(n)

        return DoubleArray(n) { best.offsetCents + best.driftCentsPerSecond * seconds[it] }
    }

    private class Fit(val offsetCents: Double, val driftCentsPerSecond: Double, val concentration: Double)

    /** Pour une dérive donnée, le décalage de départ optimal est la moyenne circulaire des écarts "dé-dérivés". */
    private fun fit(deviationsCents: DoubleArray, weights: DoubleArray, seconds: DoubleArray, drift: Double): Fit {
        var sumCos = 0.0
        var sumSin = 0.0
        var sumWeight = 0.0
        for (i in deviationsCents.indices) {
            if (weights[i] <= 0.0) continue
            val angle = 2 * PI * (deviationsCents[i] - drift * seconds[i]) / 100.0
            sumCos += weights[i] * cos(angle)
            sumSin += weights[i] * sin(angle)
            sumWeight += weights[i]
        }
        val concentration = sqrt(sumCos * sumCos + sumSin * sumSin) / sumWeight
        return Fit(atan2(sumSin, sumCos) / (2 * PI) * 100.0, drift, concentration)
    }

    /**
     * Moyenne glissante sur ±[smoothingHalfWindowMs] : couvre une période de
     * vibrato (~5-6 Hz), qui s'annule donc. Indispensable : sur un vibrato de
     * ±60 cents, les extrêmes "passent de l'autre côté" du demi-ton et la
     * moyenne circulaire frame par frame indiquerait un décalage de 50 cents.
     */
    private fun smooth(frames: List<RawPitchFrame>, raw: DoubleArray): DoubleArray {
        val n = raw.size
        val result = DoubleArray(n) { Double.NaN }
        var lo = 0
        var hi = 0
        var sum = 0.0
        var voicedCount = 0
        var totalCount = 0
        for (i in 0 until n) {
            if (raw[i].isNaN()) continue
            val t = frames[i].timestampMs
            while (hi < n && frames[hi].timestampMs <= t + smoothingHalfWindowMs) {
                totalCount++
                if (!raw[hi].isNaN()) { sum += raw[hi]; voicedCount++ }
                hi++
            }
            while (frames[lo].timestampMs < t - smoothingHalfWindowMs) {
                totalCount--
                if (!raw[lo].isNaN()) { sum -= raw[lo]; voicedCount-- }
                lo++
            }
            // Fenêtre qui chevauche un saut de note : la moyenne serait un mélange des deux.
            var straddles = false
            for (k in lo until hi) if (!raw[k].isNaN() && abs(raw[k] - raw[i]) > 1.5) { straddles = true; break }
            if (!straddles && voicedCount * 10 >= totalCount * 6) result[i] = sum / voicedCount
        }
        return result
    }

    private fun frameWeight(frames: List<RawPitchFrame>, semitones: DoubleArray, i: Int): Double {
        if (semitones[i].isNaN()) return 0.0
        val confidence = frames[i].confidence.toDouble()
        val previous = semitones.getOrNull(i - 1)?.takeUnless { it.isNaN() }
        val next = semitones.getOrNull(i + 1)?.takeUnless { it.isNaN() }
        // Frame isolée : on ne peut pas juger de sa stabilité, elle pèse peu.
        if (previous == null || next == null) return confidence * 0.3
        val slopeCents = abs(next - previous) * 100.0 / 2.0
        return confidence * exp(-(slopeCents / stableSlopeCents).pow(2))
    }
}

/** Applique une courbe de décalage ([TuningEstimator.estimate]) aux fréquences des frames. */
fun applyTuningOffsets(frames: List<RawPitchFrame>, offsetsCents: DoubleArray): List<RawPitchFrame> =
    frames.mapIndexed { i, frame ->
        if (frame.frequencyHz <= 0f) frame
        else frame.copy(frequencyHz = (frame.frequencyHz * 2.0.pow(-offsetsCents[i] / 1200.0)).toFloat())
    }
