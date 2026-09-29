package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantizerTest {

    // À 120 BPM, une double-croche (divisionsPerBeat=4) dure 125ms.
    private val bpm = 120
    private val gridMs = 125L

    @Test
    fun `note times snap to the nearest grid line`() {
        val notes = listOf(NoteEvent(midiNote = 60, startTimeMs = 810, endTimeMs = 1190, velocity = 90))

        val result = quantize(notes, bpm)

        // 810 -> plus proche de 750 (6*125) que 875 (7*125)
        assertEquals(750L, result[0].startTimeMs)
        // 1190 -> plus proche de 1250 (10*125) que 1125 (9*125)
        assertEquals(1250L, result[0].endTimeMs)
    }

    @Test
    fun `pitch and velocity are never altered`() {
        val notes = listOf(NoteEvent(midiNote = 67, startTimeMs = 333, endTimeMs = 666, velocity = 77))

        val result = quantize(notes, bpm)

        assertEquals(67, result[0].midiNote)
        assertEquals(77, result[0].velocity)
    }

    @Test
    fun `overlap introduced by rounding is clipped to keep a monophonic melody`() {
        // Arrondis indépendamment (fin de note1 -> 375, début de note2 -> 250),
        // ces deux frontières se chevauchent après arrondi (375 > 250).
        val notes = listOf(
            NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 313, velocity = 90),
            NoteEvent(midiNote = 64, startTimeMs = 270, endTimeMs = 500, velocity = 90)
        )

        val result = quantize(notes, bpm)

        assertTrue(result[0].endTimeMs <= result[1].startTimeMs)
        assertTrue(result[0].endTimeMs > result[0].startTimeMs)
    }

    @Test
    fun `every note keeps a strictly positive duration after quantization`() {
        val notes = listOf(NoteEvent(midiNote = 60, startTimeMs = 100, endTimeMs = 110, velocity = 90))

        val result = quantize(notes, bpm)

        assertTrue(result[0].endTimeMs > result[0].startTimeMs)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(0, quantize(emptyList(), bpm).size)
    }
}
