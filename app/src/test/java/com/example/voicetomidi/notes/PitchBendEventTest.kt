package com.example.voicetomidi.notes

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchBendEventTest {

    private fun frequencyAtCents(baseNote: Int, cents: Double): Float {
        val baseFreq = 440.0 * 2.0.pow((baseNote - 69) / 12.0)
        return (baseFreq * 2.0.pow(cents / 1200.0)).toFloat()
    }

    private fun frame(t: Long, note: Int, cents: Double = 0.0) =
        RawPitchFrame(timestampMs = t, frequencyHz = frequencyAtCents(note, cents), confidence = 0.9f, rmsAmplitude = 0.2f)

    @Test
    fun `a note sung exactly on pitch stays centered`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 100, velocity = 90)
        val frames = listOf(frame(0, 60, 0.0), frame(30, 60, 0.0), frame(60, 60, 0.0))

        val events = buildPitchBendCurve(listOf(note), listOf(note), frames)

        assertTrue(events.isNotEmpty())
        events.forEach { assertEquals(8192, it.value) }
    }

    @Test
    fun `a positive cents deviation bends upward proportionally`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 100, velocity = 90)
        // +50 cents sur une plage de +-200 cents -> bend = 8192 + (50/200)*8192 = 10240
        val events = buildPitchBendCurve(listOf(note), listOf(note), listOf(frame(0, 60, 50.0)))

        assertEquals(1, events.size)
        assertEquals(10240, events[0].value)
    }

    @Test
    fun `a negative cents deviation bends downward proportionally`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 100, velocity = 90)
        val events = buildPitchBendCurve(listOf(note), listOf(note), listOf(frame(0, 60, -50.0)))

        assertEquals(1, events.size)
        assertEquals(8192 - 2048, events[0].value)
    }

    @Test
    fun `frames outside the note span are ignored`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 100, endTimeMs = 200, velocity = 90)
        val frames = listOf(frame(50, 60), frame(150, 60), frame(250, 60))

        val events = buildPitchBendCurve(listOf(note), listOf(note), frames)

        assertEquals(1, events.size)
        assertEquals(150L, events[0].timestampMs)
    }

    @Test
    fun `unvoiced frames are ignored`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 100, velocity = 90)
        val unvoiced = RawPitchFrame(timestampMs = 10, frequencyHz = 0f, confidence = 0f, rmsAmplitude = 0f)

        val events = buildPitchBendCurve(listOf(note), listOf(note), listOf(unvoiced))

        assertEquals(0, events.size)
    }

    @Test
    fun `events are decimated to avoid one message per analysis frame`() {
        val note = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 200, velocity = 90)
        // Frames toutes les ~11.6ms (comme le hop réel), bien plus serré que le minimum de 20ms.
        val frames = (0..15).map { i -> frame((i * 11.6).toLong(), 60) }

        val events = buildPitchBendCurve(listOf(note), listOf(note), frames)

        assertTrue(events.size < frames.size)
    }

    @Test
    fun `timestamps remap proportionally when the output note has been quantized`() {
        val original = NoteEvent(midiNote = 60, startTimeMs = 100, endTimeMs = 300, velocity = 90) // durée 200ms
        val quantized = NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 400, velocity = 90) // durée 400ms
        // Frame à mi-chemin de la note originale (100 + 100 = 200, fraction 0.5)
        val events = buildPitchBendCurve(listOf(original), listOf(quantized), listOf(frame(200, 60)))

        assertEquals(1, events.size)
        // fraction 0.5 de la note de sortie (0..400) -> 200
        assertEquals(200L, events[0].timestampMs)
    }
}
