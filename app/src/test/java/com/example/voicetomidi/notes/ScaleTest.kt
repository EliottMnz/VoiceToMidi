package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleTest {

    @Test
    fun `chromatic scale contains every note`() {
        val constraint = NoScaleConstraint
        for (note in 48..72) assertTrue(constraint.contains(note))
    }

    @Test
    fun `chromatic scale never remaps a note`() {
        val constraint = NoScaleConstraint
        for (note in 48..72) assertEquals(note, constraint.nearestInScale(note))
    }

    @Test
    fun `C major contains only the white keys`() {
        val constraint = ScaleConstraint(Scale.MAJOR, rootPitchClass = 0) // Do majeur
        assertTrue(constraint.contains(60)) // C4
        assertTrue(constraint.contains(62)) // D4
        assertFalse(constraint.contains(61)) // C#4
        assertFalse(constraint.contains(63)) // D#4
    }

    @Test
    fun `an out-of-scale note snaps to its nearest in-scale neighbour`() {
        val constraint = ScaleConstraint(Scale.MAJOR, rootPitchClass = 0) // Do majeur
        // C#4 (61) est à 1 demi-ton de C4 (60) et 1 demi-ton de D4 (62) -> on garde la plus grave en cas d'égalité.
        assertEquals(60, constraint.nearestInScale(61))
    }

    @Test
    fun `a transposed scale shifts the allowed pitch classes`() {
        val constraint = ScaleConstraint(Scale.MAJOR, rootPitchClass = 2) // Ré majeur
        assertTrue(constraint.contains(62)) // D
        assertTrue(constraint.contains(64)) // E
        assertFalse(constraint.contains(60)) // C naturel n'est pas dans Ré majeur
    }

    @Test
    fun `pentatonic scales have fewer candidate notes than major or minor`() {
        val pentatonic = ScaleConstraint(Scale.MINOR_PENTATONIC, rootPitchClass = 0)
        val major = ScaleConstraint(Scale.MAJOR, rootPitchClass = 0)
        val allowedCount = { c: ScaleConstraint -> (60..71).count { c.contains(it) } }

        assertTrue(allowedCount(pentatonic) < allowedCount(major))
    }
}
