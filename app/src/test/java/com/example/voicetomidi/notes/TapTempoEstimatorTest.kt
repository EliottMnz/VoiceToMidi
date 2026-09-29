package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoEstimatorTest {

    @Test
    fun `a single tap is not enough to estimate a tempo`() {
        val estimator = TapTempoEstimator()
        assertNull(estimator.tap(0))
    }

    @Test
    fun `two taps 500ms apart estimate 120 BPM`() {
        val estimator = TapTempoEstimator()
        estimator.tap(0)
        assertEquals(120, estimator.tap(500))
    }

    @Test
    fun `consistent taps average out to a stable BPM`() {
        val estimator = TapTempoEstimator()
        estimator.tap(0)
        estimator.tap(600)
        estimator.tap(1200)
        assertEquals(100, estimator.tap(1800))
    }

    @Test
    fun `a long pause resets the series to only the latest run`() {
        val estimator = TapTempoEstimator(maxGapMs = 2000L)
        estimator.tap(0)
        estimator.tap(500) // ~120 BPM jusque là

        val bpm = estimator.tap(500 + 3000 + 1000) // pause de 3s (> maxGapMs), puis un tap 1s plus tard
        // La pause a vidé la série : un seul tap enregistré après le trou -> pas encore de BPM.
        assertNull(bpm)
        assertEquals(1, estimator.tapCount())
    }

    @Test
    fun `bpm is clamped to a sane musical range`() {
        val estimator = TapTempoEstimator(minBpm = 30, maxBpm = 300)
        estimator.tap(0)
        // Intervalle minuscule -> BPM extrême, doit être plafonné.
        assertEquals(300, estimator.tap(10))
    }

    @Test
    fun `reset clears all recorded taps`() {
        val estimator = TapTempoEstimator()
        estimator.tap(0)
        estimator.tap(500)
        estimator.reset()

        assertEquals(0, estimator.tapCount())
        assertNull(estimator.currentBpm())
    }
}
