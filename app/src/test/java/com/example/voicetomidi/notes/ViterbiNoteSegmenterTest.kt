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

    private fun frameAtCents(t: Long, baseNote: Int, cents: Double, confidence: Float = 0.9f, rms: Float = 0.2f): RawPitchFrame {
        val freq = (midiToFrequency(baseNote) * 2.0.pow(cents / 1200.0)).toFloat()
        return RawPitchFrame(timestampMs = t, frequencyHz = freq, confidence = confidence, rmsAmplitude = rms)
    }

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

    @Test
    fun `scale constraint keeps notes within the chosen scale`() {
        val constraint = ScaleConstraint(Scale.MAJOR, rootPitchClass = 0) // Do majeur : pas de C#4 (61)
        val segmenter = ViterbiNoteSegmenter(scaleConstraint = constraint)
        var t = 0L
        // Chanté un peu dièse par rapport à D4 (62) : doit résoudre vers D4, jamais vers C#4 qui n'existe pas dans la gamme.
        val frames = (0 until 40).map { frameAtCents(t, baseNote = 62, cents = 25.0).also { t += frameDurationMs } }

        val events = segmenter.segment(frames)

        assertEquals(1, events.size)
        assertEquals(62, events[0].midiNote)
    }

    @Test
    fun `a brief octave-high blip at the very start is corrected to match the next note`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(15) { frames += frame(t, 72); t += frameDurationMs } // Do5, artefact bref (150ms)
        repeat(40) { frames += frame(t, 60); t += frameDurationMs } // Do4, la vraie note tenue

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(60, events[0].midiNote) // corrigé : plus de saut d'octave
        assertEquals(60, events[1].midiNote)
    }

    @Test
    fun `a sustained octave-high note at the start is not corrected if it lasts too long`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(60) { frames += frame(t, 72); t += frameDurationMs } // Do5 tenu 600ms : note délibérée, pas un artefact
        repeat(40) { frames += frame(t, 60); t += frameDurationMs } // Do4

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(72, events[0].midiNote) // pas corrigé : trop long pour être un artefact
        assertEquals(60, events[1].midiNote)
    }

    @Test
    fun `a brief leading note that is not an octave away is left untouched`() {
        val segmenter = ViterbiNoteSegmenter()
        var t = 0L
        val frames = mutableListOf<RawPitchFrame>()
        repeat(15) { frames += frame(t, 67); t += frameDurationMs } // Sol4, bref mais pas une octave de la suivante
        repeat(40) { frames += frame(t, 60); t += frameDurationMs } // Do4

        val events = segmenter.segment(frames)

        assertEquals(2, events.size)
        assertEquals(67, events[0].midiNote) // pas touché : pas une relation d'octave
        assertEquals(60, events[1].midiNote)
    }
}
