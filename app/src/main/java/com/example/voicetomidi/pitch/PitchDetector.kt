package com.example.voicetomidi.pitch

data class PitchResult(
    val frequencyHz: Float,
    val confidence: Float
) {
    companion object {
        val Unvoiced = PitchResult(frequencyHz = 0f, confidence = 0f)
    }
}

interface PitchDetector {
    val sampleRate: Int

    /** Analyse une fenêtre d'échantillons normalisés [-1, 1] et estime la hauteur perçue. */
    fun detectPitch(window: FloatArray): PitchResult
}
