package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDetectorTest {

    private fun melody(vararg notes: Int) = notes.map { WeightedPitch(it.toDouble(), 1.0) }

    @Test
    fun `a C major melody is detected as C major with high confidence`() {
        val keys = KeyDetector.detect(melody(60, 62, 64, 65, 67, 69, 71, 72, 67, 64, 60, 65, 69, 67, 60))

        val best = keys.best
        assertEquals(0, best.tonicPitchClass)
        assertFalse(best.isMinor)
        assertTrue("confiance ${best.confidence}", best.confidence > 0.8)
        assertEquals("Do majeur", best.displayName)
    }

    @Test
    fun `an A minor melody is detected as A minor`() {
        val keys = KeyDetector.detect(melody(57, 60, 64, 57, 59, 60, 62, 64, 62, 60, 59, 57, 64, 57))

        val best = keys.best
        assertEquals(9, best.tonicPitchClass)
        assertTrue(best.isMinor)
    }

    @Test
    fun `three notes leave the key uncertain`() {
        val best = KeyDetector.detect(melody(60, 64, 67)).best
        assertTrue("confiance ${best.confidence}", best.confidence < 0.6)
    }

    @Test
    fun `out-of-key notes get a low prior only when the key is certain`() {
        val certain = KeyDetector.detect(melody(60, 62, 64, 65, 67, 69, 71, 72, 67, 64, 60, 65, 69, 67, 60, 62, 64, 65))
        val uncertain = KeyDetector.detect(melody(60, 64))

        assertTrue(certain.pitchClassPrior(6, 0.0005) < 0.1) // Fa#
        assertTrue(certain.pitchClassPrior(4, 0.0005) > 0.99) // Mi
        assertTrue(uncertain.pitchClassPrior(6, 0.0005) > certain.pitchClassPrior(6, 0.0005))
    }

    @Test
    fun `no notes gives a flat prior`() {
        val keys = KeyDetector.detect(emptyList())
        val priors = (0 until 12).map { keys.pitchClassPrior(it, 0.0005) }
        assertTrue(priors.all { kotlin.math.abs(it - priors[0]) < 1e-9 })
    }
}
