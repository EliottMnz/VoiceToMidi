package com.example.voicetomidi.notes

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViterbiNoteSegmenterTest {

    private val frameDurationMs = 10L

    private fun midiToFrequency(note: Int): Float =
        (440.0 * 2.0.pow((note - 69) / 12.0)).toFloat()

    private fun frame(t: Long, note: Int, confidence: Float = 0.9f, rms: Float = 0.2f) =
        RawPitchFrame(timestampMs = t, frequencyHz = midiToFrequency(note), confidence = confidence, rmsAmplitude = rms)

    private fun silentFrame(t: Long) =
        RawPitchFrame(timestampMs = t, frequencyHz = 0f, confidence = 0f, rmsAmplitude = 0f)

    @Test
    fun `a held note with scattered single-frame jitter yields a single note`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = (0 until 60).map { i ->
            val note = if (i % 7 == 3) 70 else 69 // dévie isolément, jamais deux fois de suite
            frame(t, note).also { t += frameDurationMs }
        }

        val events = segmenter.segment(frames)

        assertEquals(1, events.size)
        assertEquals(69, events[0].midiNote)
    }

    @Test
    fun `two notes separated by clear silence are recovered as two distinct notes`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(40) { frames += frame(t, 60); t += frameDurationMs }
        repeat(30) { frames += silentFrame(t); t += frameDurationMs }
        repeat(40) { frames += frame(t, 64); t += frameDurationMs }

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(60, events[0].midiNote)
        assertEquals(64, events[1].midiNote)
        assertTrue(events[1].startTimeMs > events[0].endTimeMs)
    }

    @Test
    fun `two notes sung legato without silence produce two notes`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(40) { frames += frame(t, 65); t += frameDurationMs }
        repeat(40) { frames += frame(t, 72); t += frameDurationMs }

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(65, events[0].midiNote)
        assertEquals(72, events[1].midiNote)
    }

    @Test
    fun `a brief transient reading at a note boundary does not create a spurious short note`() {
        // Reproduit le cas observé en pratique : une transition brutale entre deux notes
        // (grand saut, sans glissement) fait qu'une ou deux fenêtres d'analyse chevauchent
        // la jointure et mesurent une fréquence transitoire proche d'une note tierce.
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(40) { frames += frame(t, 64); t += frameDurationMs } // Mi4
        repeat(2) { frames += frame(t, 65); t += frameDurationMs } // lecture parasite (20ms)
        repeat(40) { frames += frame(t, 67); t += frameDurationMs } // Sol4

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(64, events[0].midiNote)
        assertEquals(67, events[1].midiNote)
    }

    @Test
    fun `an empty frame list produces no notes`() {
        assertEquals(0, ViterbiNoteSegmenter().segment(emptyList()).size)
    }
}
