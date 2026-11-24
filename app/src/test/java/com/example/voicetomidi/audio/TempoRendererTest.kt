package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.NoteEvent
import org.junit.Assert.*
import org.junit.Test

class TempoRendererTest {
    @Test fun `le changement de BPM preserve la position sans sauter et ne transpose pas`() {
        val renderer = TempoRenderer(listOf(NoteEvent(69, 0, 10000, 100)))
        val second = ShortArray(TempoRenderer.SAMPLE_RATE)
        renderer.render(second)
        assertEquals(1000.0, renderer.positionMs, 0.001)
        renderer.bpm = 240
        renderer.render(second)
        assertEquals(3000.0, renderer.positionMs, 0.001)
        // Le La reste a 440 Hz meme a tempo double.
        val crossings = second.indices.drop(1).count { second[it - 1] <= 0 && second[it] > 0 }
        assertEquals(440.0, crossings.toDouble(), 2.0)
        renderer.bpm = 60
        renderer.render(second)
        assertEquals(3500.0, renderer.positionMs, 0.001)
    }

    @Test fun `le seek rejoue les notes tenues et ignore les notes terminees`() {
        val notes = listOf(NoteEvent(60, 0, 100, 90), NoteEvent(69, 500, 2000, 100))
        val renderer = TempoRenderer(notes, 1000.0)
        val buffer = ShortArray(4410)
        renderer.render(buffer)
        assertTrue(buffer.any { it.toInt() != 0 })
        val finished = TempoRenderer(notes, 3000.0)
        finished.render(buffer)
        assertTrue(finished.finished)
        assertTrue(buffer.all { it.toInt() == 0 })
    }

    @Test fun `un silence reste silencieux et les notes finissent sans saturation`() {
        val renderer = TempoRenderer(listOf(NoteEvent(69, 100, 200, 127)))
        val silence = ShortArray(2205)
        renderer.render(silence)
        assertTrue(silence.all { it.toInt() == 0 })
        renderer.render(ShortArray(22050))
        assertTrue(renderer.finished)
    }
}
