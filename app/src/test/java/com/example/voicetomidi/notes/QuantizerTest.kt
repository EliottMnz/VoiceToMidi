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

    @Test
    fun `estimateGridMs finds the median gap between note onsets`() {
        // Onsets espacés de 200, 200, 600ms -> mediane = 200ms (le 600 est un outlier).
        val notes = listOf(
            NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 150, velocity = 90),
            NoteEvent(midiNote = 62, startTimeMs = 200, endTimeMs = 350, velocity = 90),
            NoteEvent(midiNote = 64, startTimeMs = 400, endTimeMs = 550, velocity = 90),
            NoteEvent(midiNote = 65, startTimeMs = 1000, endTimeMs = 1150, velocity = 90)
        )

        assertEquals(200.0, estimateGridMs(notes), 0.01)
    }

    @Test
    fun `estimateGridMs falls back to a default when there is not enough data`() {
        assertEquals(125.0, estimateGridMs(emptyList()), 0.01)
        assertEquals(125.0, estimateGridMs(listOf(NoteEvent(60, 0, 100, 90))), 0.01)
    }

    @Test
    fun `quantizeAuto snaps notes to a grid derived from the actual performance`() {
        // Chanté "vite" (gap de 100ms) : quantizeAuto doit suivre ce tempo, pas 120 BPM fixe.
        val notes = listOf(
            NoteEvent(midiNote = 60, startTimeMs = 5, endTimeMs = 95, velocity = 90),
            NoteEvent(midiNote = 62, startTimeMs = 102, endTimeMs = 195, velocity = 90),
            NoteEvent(midiNote = 64, startTimeMs = 198, endTimeMs = 290, velocity = 90)
        )

        val result = quantizeAuto(notes)

        // Grille attendue ~100ms (médiane des écarts 97, 96) -> chaque note tombe sur un multiple de ~100ms.
        result.forEach { note ->
            val remainder = note.startTimeMs % 100
            assertTrue("startTimeMs=${note.startTimeMs} pas proche d'un multiple de 100ms", remainder < 15 || remainder > 85)
        }
    }
}
