package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ChordsTest {

    private val cMajor = ScaleConstraint(Scale.MAJOR, rootPitchClass = 0)
    private val aMinor = ScaleConstraint(Scale.NATURAL_MINOR, rootPitchClass = 9)

    @Test
    fun `a triad stacks every other note of the key`() {
        // Do → Do Mi Sol ; Ré → Ré Fa La (mineur, comme dans la gamme de Do).
        assertEquals(listOf(60, 64, 67), chordPitches(60, 3, cMajor))
        assertEquals(listOf(62, 65, 69), chordPitches(62, 3, cMajor))
    }

    @Test
    fun `bigger chords keep stacking thirds`() {
        assertEquals(listOf(60, 64, 67, 71, 74, 77), chordPitches(60, 6, cMajor))
        assertEquals(listOf(57, 60, 64, 67), chordPitches(57, 4, aMinor))
    }

    @Test
    fun `one note is no chord, and size is capped`() {
        assertEquals(listOf(60), chordPitches(60, 1, cMajor))
        assertEquals(MAX_CHORD_SIZE, chordPitches(60, 50, cMajor).size)
    }

    @Test
    fun `without a key the chord is major`() {
        assertEquals(listOf(61, 65, 68), chordPitches(61, 3, null))
        assertEquals(listOf(61, 65, 68), chordPitches(61, 3, ScaleConstraint(Scale.BLUES, 0)))
    }

    @Test
    fun `chord tones share the timing of their note`() {
        val notes = listOf(
            NoteEvent(60, 0, 400, 100),
            NoteEvent(62, 500, 900, 80)
        )

        val all = withChords(notes, mapOf(1 to Chord(3)), cMajor)

        assertEquals(listOf(60, 62, 65, 69), all.map { it.midiNote })
        assertEquals(listOf(0L, 500L, 500L, 500L), all.map { it.startTimeMs })
        assertEquals(80, all.last().velocity)
    }

    @Test
    fun `removing a note shifts the chords after it`() {
        assertEquals(mapOf(0 to Chord(3), 2 to Chord(4)), mapOf(0 to Chord(3), 1 to Chord(5), 3 to Chord(4)).afterRemoving(1))
    }

    @Test
    fun `auto replaces the tense diminished chord by a minor one`() {
        // Si en Do majeur : Si Ré Fa (diminué) devient Si Ré Fa# (mineur).
        assertEquals(listOf(71, 74, 78), chordPitches(71, 3, cMajor))
    }

    @Test
    fun `a chosen colour wins over the key`() {
        // Ré en Do majeur serait mineur ; imposé majeur : Ré Fa# La.
        assertEquals(listOf(62, 66, 69), chordPitches(62, 3, cMajor, ChordColor.MAJOR))
        // Do imposé mineur : Do Mi♭ Sol Si♭.
        assertEquals(listOf(60, 63, 67, 70), chordPitches(60, 4, cMajor, ChordColor.MINOR))
    }
}
