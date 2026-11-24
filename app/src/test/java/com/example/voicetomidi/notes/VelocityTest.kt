package com.example.voicetomidi.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityTest {

    @Test
    fun `percent covers the whole MIDI range`() {
        assertEquals(0, velocityFromPercent(0))
        assertEquals(64, velocityFromPercent(50))
        assertEquals(127, velocityFromPercent(100))
        assertEquals(127, velocityFromPercent(150))
    }

    @Test
    fun `every 5 percent step survives the round trip`() {
        for (percent in 0..100 step 5) assertEquals(percent, velocityToPercent(velocityFromPercent(percent)))
    }

    @Test
    fun `a take gets the same velocity whatever the singing volume`() {
        val notes = listOf(
            NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 400, velocity = 41),
            NoteEvent(midiNote = 62, startTimeMs = 500, endTimeMs = 900, velocity = 127)
        )

        val uniform = notes.withUniformVelocity()

        assertTrue(uniform.all { it.velocity == velocityFromPercent(DEFAULT_VELOCITY_PERCENT) })
        assertEquals(notes.map { it.midiNote to it.startTimeMs }, uniform.map { it.midiNote to it.startTimeMs })
    }
}
