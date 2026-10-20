package com.example.voicetomidi.notes

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** Une note chantée vue par la détection de tonalité : hauteur continue (en demi-tons MIDI) et poids. */
data class WeightedPitch(val semitones: Double, val weight: Double)

private val FRENCH_PITCH_CLASS_NAMES =
    arrayOf("Do", "Do#", "Ré", "Mi♭", "Mi", "Fa", "Fa#", "Sol", "La♭", "La", "Si♭", "Si")

private val MAJOR_STEPS = setOf(0, 2, 4, 5, 7, 9, 11)
private val MINOR_STEPS = setOf(0, 2, 3, 5, 7, 8, 10)

// Profils de Krumhansl-Kessler : à quel point chaque degré "appartient" à une
// tonalité majeure/mineure, mesuré sur des auditeurs. Élevés à la puissance
// PROFILE_SHARPNESS puis normalisés en probabilités : bruts, ils sont trop
// timides (15 notes d'une gamme de Do ne donnaient que 68 % de confiance) ;
// avec 2, une vraie mélodie tranche (~94 %) et 3 notes restent hésitantes (~35 %).
private const val PROFILE_SHARPNESS = 2.0
private val MAJOR_PROFILE = sharpen(doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88))
private val MINOR_PROFILE = sharpen(doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17))

private fun sharpen(values: DoubleArray): DoubleArray {
    val powered = values.map { it.pow(PROFILE_SHARPNESS) }
    val sum = powered.sum()
    return DoubleArray(values.size) { powered[it] / sum }
}

data class DetectedKey(val tonicPitchClass: Int, val isMinor: Boolean, val confidence: Double) {
    val displayName: String
        get() = "${FRENCH_PITCH_CLASS_NAMES[tonicPitchClass]} ${if (isMinor) "mineur" else "majeur"}"
}

/**
 * Probabilité de chacune des 24 tonalités (indices 0..11 : majeur sur Do..Si,
 * 12..23 : mineur sur Do..Si) au vu des notes chantées.
 */
class KeyDistribution internal constructor(private val probabilities: DoubleArray) {

    private fun inKey(keyIndex: Int, pitchClass: Int): Boolean {
        val tonic = keyIndex % 12
        val steps = if (keyIndex < 12) MAJOR_STEPS else MINOR_STEPS
        return (pitchClass - tonic).mod(12) in steps
    }

    /** La tonalité la plus probable ; sa confiance est celle de son *jeu de notes* (majeur et relatif mineur confondus). */
    val best: DetectedKey
        get() {
            val bestIndex = probabilities.indices.maxBy { probabilities[it] }
            val tonic = bestIndex % 12
            val isMinor = bestIndex >= 12
            val relative = if (isMinor) (tonic + 3) % 12 else 12 + (tonic + 9) % 12
            return DetectedKey(tonic, isMinor, probabilities[bestIndex] + probabilities[relative])
        }

    /**
     * Version "tranchée" des probabilités pour la correction : un verdict à
     * 98 % devient quasi certain (les 2 % restants suffiraient sinon à sauver
     * n'importe quelle note hors gamme), tandis qu'une vraie hésitation
     * (50/50 entre deux gammes) reste une hésitation.
     */
    private val decisiveProbabilities: DoubleArray by lazy {
        val powered = DoubleArray(probabilities.size) { probabilities[it].pow(DECISIVENESS) }
        val total = powered.sum()
        DoubleArray(powered.size) { powered[it] / total }
    }

    /**
     * Probabilité a priori qu'une note de classe [pitchClass] soit voulue :
     * moyenne, sur toutes les tonalités pondérées par leur probabilité, de
     * 1 (dans la gamme) ou [outOfKeyWeight] (hors gamme). Si la tonalité est
     * incertaine, les notes douteuses gardent une probabilité intermédiaire —
     * la correction s'adoucit d'elle-même.
     */
    fun pitchClassPrior(pitchClass: Int, outOfKeyWeight: Double): Double {
        var prior = 0.0
        for (k in decisiveProbabilities.indices) {
            prior += decisiveProbabilities[k] * if (inKey(k, pitchClass.mod(12))) 1.0 else outOfKeyWeight
        }
        return prior
    }

    private companion object {
        const val DECISIVENESS = 3.0
    }
}

/**
 * Trouve la tonalité la plus probable d'une mélodie, de façon bayésienne :
 * chaque note "vote" pour les tonalités où elle est à sa place (profils de
 * Krumhansl-Kessler), avec un poids lié à sa durée. Plus il y a de notes,
 * plus le verdict est tranché ; sur 3 notes il reste naturellement hésitant.
 * Une note à mi-chemin entre deux demi-tons vote pour les deux.
 */
object KeyDetector {

    private const val ASSIGNMENT_SIGMA_SEMITONES = 0.3

    fun detect(notes: List<WeightedPitch>): KeyDistribution {
        val logLikelihoods = DoubleArray(24)
        for (note in notes) {
            val assignment = softPitchClassAssignment(note.semitones)
            for (k in 0 until 24) {
                val profile = if (k < 12) MAJOR_PROFILE else MINOR_PROFILE
                val tonic = k % 12
                var p = 0.0
                for ((pitchClass, share) in assignment) p += share * profile[(pitchClass - tonic).mod(12)]
                logLikelihoods[k] += note.weight * ln(p)
            }
        }
        val max = logLikelihoods.max()
        val unnormalized = DoubleArray(24) { exp(logLikelihoods[it] - max) }
        val total = unnormalized.sum()
        return KeyDistribution(DoubleArray(24) { unnormalized[it] / total })
    }

    private fun softPitchClassAssignment(semitones: Double): List<Pair<Int, Double>> {
        val low = floor(semitones).toInt()
        val candidates = listOf(low, low + 1)
        val likelihoods = candidates.map { c ->
            val d = semitones - c
            exp(-(d * d) / (2 * ASSIGNMENT_SIGMA_SEMITONES * ASSIGNMENT_SIGMA_SEMITONES))
        }
        val sum = likelihoods.sum()
        return candidates.indices.map { candidates[it].mod(12) to likelihoods[it] / sum }
    }
}
