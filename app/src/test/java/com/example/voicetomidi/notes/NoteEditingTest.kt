package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteEditingTest {

    private val notes = listOf(
        NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 400, velocity = 90),
        NoteEvent(midiNote = 62, startTimeMs = 600, endTimeMs = 1000, velocity = 90),
        NoteEvent(midiNote = 64, startTimeMs = 1500, endTimeMs = 2000, velocity = 90)
    )

    @Test
    fun `moving keeps the duration and changes the pitch`() {
        val moved = editedNote(notes, 1, NoteGrab.MOVE, deltaMs = 100, deltaSemitones = 3)

        assertEquals(NoteEvent(65, 700, 1100, 90), moved)
    }

    @Test
    fun `moving stops against the neighbours`() {
        assertEquals(400L, editedNote(notes, 1, NoteGrab.MOVE, deltaMs = -900, deltaSemitones = 0).startTimeMs)
        assertEquals(1500L, editedNote(notes, 1, NoteGrab.MOVE, deltaMs = 900, deltaSemitones = 0).endTimeMs)
    }

    @Test
    fun `stretching the end only moves the end, up to the next note`() {
        val longer = editedNote(notes, 1, NoteGrab.END, deltaMs = 300, deltaSemitones = 5)
        assertEquals(NoteEvent(62, 600, 1300, 90), longer)

        assertEquals(1500L, editedNote(notes, 1, NoteGrab.END, deltaMs = 5000, deltaSemitones = 0).endTimeMs)
    }

    @Test
    fun `a note never shrinks below the minimum duration`() {
        val shortEnd = editedNote(notes, 1, NoteGrab.END, deltaMs = -5000, deltaSemitones = 0)
        assertEquals(600L + MIN_EDITED_NOTE_MS, shortEnd.endTimeMs)

        val shortStart = editedNote(notes, 1, NoteGrab.START, deltaMs = 5000, deltaSemitones = 0)
        assertEquals(1000L - MIN_EDITED_NOTE_MS, shortStart.startTimeMs)
    }

    @Test
    fun `stretching the start stops at the previous note and at the frame`() {
        assertEquals(400L, editedNote(notes, 1, NoteGrab.START, deltaMs = -5000, deltaSemitones = 0).startTimeMs)
        assertEquals(0L, editedNote(notes, 0, NoteGrab.START, deltaMs = -5000, deltaSemitones = 0).startTimeMs)
        assertEquals(2500L, editedNote(notes, 2, NoteGrab.END, deltaMs = 5000, deltaSemitones = 0, maxMs = 2500).endTimeMs)
    }

    @Test
    fun `pitch stays a valid MIDI note`() {
        assertEquals(127, editedNote(notes, 0, NoteGrab.MOVE, deltaMs = 0, deltaSemitones = 200).midiNote)
        assertEquals(0, editedNote(notes, 0, NoteGrab.MOVE, deltaMs = 0, deltaSemitones = -200).midiNote)
    }
}
