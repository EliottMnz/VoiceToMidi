package com.example.voicetomidi.pitch

/**
 * Implémentation de l'algorithme YIN (de Cheveigné & Kawahara, 2002) pour
 * l'estimation de la fréquence fondamentale d'un signal monophonique.
 */
class YinPitchDetector(
    override val sampleRate: Int,
    private val threshold: Float = 0.15f,
    private val minFrequencyHz: Float = 50f,
    private val maxFrequencyHz: Float = 1500f
) : PitchDetector {

    override fun detectPitch(window: FloatArray): PitchResult {
        val maxTau = minOf(window.size / 2, (sampleRate / minFrequencyHz).toInt())
        val minTau = maxOf(2, (sampleRate / maxFrequencyHz).toInt())
        if (maxTau <= minTau) return PitchResult.Unvoiced

        val difference = differenceFunction(window, maxTau)
        val cmnd = cumulativeMeanNormalizedDifference(difference, maxTau)
        val tauEstimate = absoluteThreshold(cmnd, minTau, maxTau) ?: return PitchResult.Unvoiced

        val refinedTau = parabolicInterpolation(cmnd, tauEstimate, maxTau)
        val frequency = sampleRate / refinedTau
        val confidence = (1f - cmnd[tauEstimate]).coerceIn(0f, 1f)

        return PitchResult(frequencyHz = frequency, confidence = confidence)
    }

    private fun differenceFunction(window: FloatArray, maxTau: Int): FloatArray {
        val difference = FloatArray(maxTau + 1)
        for (tau in 1..maxTau) {
            var sum = 0f
            for (i in 0 until window.size - tau) {
                val delta = window[i] - window[i + tau]
                sum += delta * delta
            }
            difference[tau] = sum
        }
        return difference
    }

    private fun cumulativeMeanNormalizedDifference(difference: FloatArray, maxTau: Int): FloatArray {
        val cmnd = FloatArray(maxTau + 1)
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            runningSum += difference[tau]
            cmnd[tau] = if (runningSum == 0f) 1f else difference[tau] * tau / runningSum
        }
        return cmnd
    }

    /** Premier minimum local passant sous [threshold], en partant de [minTau]. */
    private fun absoluteThreshold(cmnd: FloatArray, minTau: Int, maxTau: Int): Int? {
        var tau = minTau
        while (tau <= maxTau) {
            if (cmnd[tau] < threshold) {
                var better = tau
                while (better + 1 <= maxTau && cmnd[better + 1] < cmnd[better]) {
                    better++
                }
                return better
            }
            tau++
        }
        return null
    }

    /** Affine l'estimation entière de tau par interpolation parabolique autour du minimum. */
    private fun parabolicInterpolation(cmnd: FloatArray, tau: Int, maxTau: Int): Float {
        val x0 = if (tau < 1) tau else tau - 1
        val x2 = if (tau + 1 <= maxTau) tau + 1 else tau
        if (x0 == tau) {
            return if (cmnd[tau] <= cmnd[x2]) tau.toFloat() else x2.toFloat()
        }
        if (x2 == tau) {
            return if (cmnd[tau] <= cmnd[x0]) tau.toFloat() else x0.toFloat()
        }
        val s0 = cmnd[x0]
        val s1 = cmnd[tau]
        val s2 = cmnd[x2]
        val denom = 2 * s1 - s2 - s0
        return if (denom == 0f) tau.toFloat() else tau + (s2 - s0) / (2 * denom)
    }
}
