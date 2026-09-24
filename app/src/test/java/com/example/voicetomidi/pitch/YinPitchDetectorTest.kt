package com.example.voicetomidi.pitch

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YinPitchDetectorTest {

    private val sampleRate = 44100

    @Test
    fun `detects a 440 Hz sine wave as A4`() {
        val detector = YinPitchDetector(sampleRate)
        val window = generateSineWave(frequencyHz = 440f, size = 2048)

        val result = detector.detectPitch(window)

        assertEquals(440f, result.frequencyHz, 2f)
        assertTrue(result.confidence > 0.8f)
    }

    @Test
    fun `detects a 220 Hz sine wave as A3`() {
        val detector = YinPitchDetector(sampleRate)
        val window = generateSineWave(frequencyHz = 220f, size = 2048)

        val result = detector.detectPitch(window)

        assertEquals(220f, result.frequencyHz, 2f)
    }

    @Test
    fun `returns unvoiced for silence`() {
        val detector = YinPitchDetector(sampleRate)
        val window = FloatArray(2048)

        val result = detector.detectPitch(window)

        assertEquals(PitchResult.Unvoiced, result)
    }

    private fun generateSineWave(frequencyHz: Float, size: Int): FloatArray =
        FloatArray(size) { i -> sin(2.0 * PI * frequencyHz * i / sampleRate).toFloat() }
}
