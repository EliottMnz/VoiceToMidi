package com.example.voicetomidi.notes

import com.example.voicetomidi.pitch.PitchResult
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSegmenterTest {

    private val frameDurationMs = 10.0

    private fun segmenter() = NoteSegmenter(
        frameDurationMs = frameDurationMs,
        smoothingWindowMs = 60.0,
        minOnsetMs = 60.0,
        minNoteChangeMs = 100.0,
        changeConfidenceRatio = 0.7,
        changeThresholdCents = 70.0,
        bigLeapThresholdCents = 150.0,
        fastChangeMs = 30.0,
        minOffsetMs = 120.0,
        minConfidence = 0.5f
    )

    private fun midiToFrequency(note: Int): Float =
        (440.0 * 2.0.pow((note - 69) / 12.0)).toFloat()

    private fun frequencyAtCents(baseNote: Int, cents: Double): Float =
        (midiToFrequency(baseNote) * 2.0.pow(cents / 1200.0)).toFloat()

    private fun voicedFrame(note: Int, confidence: Float = 0.9f) =
        PitchResult(frequencyHz = midiToFrequency(note), confidence = confidence)

    private val silentFrame = PitchResult.Unvoiced

    @Test
    fun `a held note with brief single-frame jitter yields a single note event`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L
        // Une frame isolée sur 7 dévie d'un demi-ton (simule du vibrato/bruit),
        // jamais deux d'affilée : le filtre médian (fenêtre de 6 frames) doit absorber ça.
        val rawSequence = List(40) { i -> if (i % 7 == 3) 70 else 69 }

        for (note in rawSequence) {
            segmenter.processFrame(t, voicedFrame(note), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(1, events.size)
        assertEquals(69, events[0].midiNote)
    }

    @Test
    fun `two notes sung legato without silence produce two note events`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(50) {
            segmenter.processFrame(t, voicedFrame(69), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(50) {
            segmenter.processFrame(t, voicedFrame(74), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(2, events.size)
        assertEquals(69, events[0].midiNote)
        assertEquals(74, events[1].midiNote)
        assertEquals(events[0].endTimeMs, events[1].startTimeMs)
    }

    @Test
    fun `two notes separated by silence produce two distinct note events with a gap`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(50) {
            segmenter.processFrame(t, voicedFrame(60), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(20) {
            segmenter.processFrame(t, silentFrame, rmsAmplitude = 0f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(50) {
            segmenter.processFrame(t, voicedFrame(64), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(2, events.size)
        assertEquals(60, events[0].midiNote)
        assertEquals(64, events[1].midiNote)
        assertTrue(events[1].startTimeMs > events[0].endTimeMs)
    }

    @Test
    fun `a vibrato swing shorter than the change hysteresis does not split the note`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(50) {
            segmenter.processFrame(t, voicedFrame(69), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(8) { // 80ms de flottement vers la note voisine, sous minNoteChangeMs=100ms
            segmenter.processFrame(t, voicedFrame(70), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(50) {
            segmenter.processFrame(t, voicedFrame(69), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(1, events.size)
        assertEquals(69, events[0].midiNote)
    }

    @Test
    fun `an imprecise singer wobbling near a note boundary does not trigger a note change`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(50) {
            segmenter.processFrame(t, voicedFrame(69), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        // Dérive jusqu'à 65 cents autour de la note tenue (sous la zone morte de
        // 70 cents), y compris des valeurs qui, arrondies naïvement au demi-ton
        // le plus proche, tomberaient parfois sur la note voisine (70).
        val wobbleCents = listOf(40.0, 58.0, 63.0, -45.0, 55.0, -60.0, 65.0, -30.0, 50.0, -65.0)
        repeat(80) { i ->
            val freq = frequencyAtCents(69, wobbleCents[i % wobbleCents.size])
            segmenter.processFrame(t, PitchResult(frequencyHz = freq, confidence = 0.9f), rmsAmplitude = 0.2f)
                ?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(1, events.size)
        assertEquals(69, events[0].midiNote)
    }

    @Test
    fun `a sustained semitone shift longer than the hysteresis is confirmed as a new note`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(50) {
            segmenter.processFrame(t, voicedFrame(69), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(50) { // 500ms tenus sur la note voisine : un vrai changement de note
            segmenter.processFrame(t, voicedFrame(70), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(2, events.size)
        assertEquals(69, events[0].midiNote)
        assertEquals(70, events[1].midiNote)
    }

    @Test
    fun `a fast arpeggio with large intervals is tracked as distinct short notes`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(30) { // établit la première note (Do)
            segmenter.processFrame(t, voicedFrame(60), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        // Arpège rapide : Mi (+400c), Sol (+300c sur Mi), Do octave (+500c sur Sol) —
        // tous largement au-dessus de bigLeapThresholdCents=150, donc confirmation
        // rapide (fastChangeMs=30ms) même si chaque note ne dure que 120ms.
        for (note in listOf(64, 67, 72)) {
            repeat(12) {
                segmenter.processFrame(t, voicedFrame(note), rmsAmplitude = 0.2f)?.let(events::add)
                t += frameDurationMs.toLong()
            }
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(4, events.size)
        assertEquals(60, events[0].midiNote)
        assertEquals(64, events[1].midiNote)
        assertEquals(67, events[2].midiNote)
        assertEquals(72, events[3].midiNote)
    }

    @Test
    fun `a short confidence dropout inside a note does not split it`() {
        val segmenter = segmenter()
        val events = mutableListOf<NoteEvent>()
        var t = 0L

        repeat(30) {
            segmenter.processFrame(t, voicedFrame(65), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(5) { // 50 ms de coupure, sous le seuil minOffsetMs=120ms
            segmenter.processFrame(t, silentFrame, rmsAmplitude = 0f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        repeat(30) {
            segmenter.processFrame(t, voicedFrame(65), rmsAmplitude = 0.2f)?.let(events::add)
            t += frameDurationMs.toLong()
        }
        segmenter.finish(t)?.let(events::add)

        assertEquals(1, events.size)
        assertEquals(65, events[0].midiNote)
    }
}
