package com.example.voicetomidi.notes

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class TuningEstimatorTest {

    private fun frameAt(t: Long, semitones: Double) = RawPitchFrame(
        timestampMs = t,
        frequencyHz = (440.0 * 2.0.pow((semitones - 69) / 12.0)).toFloat(),
        confidence = 0.9f,
        rmsAmplitude = 0.2f
    )

    @Test
    fun `a constant offset is measured`() {
        val notes = listOf(60, 64, 67, 72)
        val frames = (0 until 400).map { i -> frameAt(i * 10L, notes[i / 100] - 0.4) }

        val offsets = TuningEstimator().estimate(frames)

        offsets.forEach { assertEquals(-40.0, it, 3.0) }
    }

    @Test
    fun `a drift past half a semitone stays continuous`() {
        // Dérive de 0 à +80 cents sur 16 s : un suivi naïf sauterait de +50 à -50.
        val frames = (0 until 1600).map { i -> frameAt(i * 10L, 60.0 + (i / 100) % 3 * 2 + 0.8 * i / 1600) }

        val offsets = TuningEstimator().estimate(frames)

        for (k in 1 until offsets.size) assertEquals(offsets[k - 1], offsets[k], 5.0)
        assertEquals(80.0, offsets.last(), 15.0)
    }

    @Test
    fun `random note-to-note errors are not mistaken for a drift`() {
        val random = kotlin.random.Random(9)
        val notes = List(17) { listOf(60, 62, 64, 65, 67)[it % 5] + (random.nextDouble() - 0.5) * 0.8 }
        val frames = (0 until 1700).map { i -> frameAt(i * 10L, notes[i / 100]) }

        val offsets = TuningEstimator().estimate(frames)

        assertEquals(offsets.first(), offsets.last(), 1e-9)
    }

    @Test
    fun `no voiced frames gives no correction`() {
        val frames = (0 until 50).map { RawPitchFrame(it * 10L, 0f, 0f, 0f) }
        TuningEstimator().estimate(frames).forEach { assertEquals(0.0, it, 0.0) }
    }
}
