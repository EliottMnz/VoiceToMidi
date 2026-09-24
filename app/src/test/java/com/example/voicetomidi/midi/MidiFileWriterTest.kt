package com.example.voicetomidi.midi

import com.example.voicetomidi.notes.NoteEvent
import java.io.ByteArrayInputStream
import javax.sound.midi.MidiSystem
import javax.sound.midi.ShortMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ces tests s'exécutent sur la JVM hôte (pas sur Android), où javax.sound.midi
 * est disponible : on l'utilise uniquement comme "oracle" de confiance pour
 * relire et valider les octets produits par notre propre écrivain SMF, sans en
 * faire une dépendance de l'app (javax.sound.midi n'existe pas sur Android).
 */
class MidiFileWriterTest {

    @Test
    fun `written file is parsed back with correct notes and velocities`() {
        val notes = listOf(
            NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 500, velocity = 100),
            NoteEvent(midiNote = 64, startTimeMs = 500, endTimeMs = 1200, velocity = 80)
        )

        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().write(notes)))
        val track = sequence.tracks.single()

        val noteOns = (0 until track.size())
            .map(track::get)
            .mapNotNull { it.message as? ShortMessage }
            .filter { it.command == ShortMessage.NOTE_ON }

        assertEquals(2, noteOns.size)
        assertEquals(60, noteOns[0].data1)
        assertEquals(100, noteOns[0].data2)
        assertEquals(64, noteOns[1].data1)
        assertEquals(80, noteOns[1].data2)
    }

    @Test
    fun `note timing round-trips within a few milliseconds`() {
        val notes = listOf(NoteEvent(midiNote = 69, startTimeMs = 1000, endTimeMs = 2000, velocity = 100))

        val bytes = MidiFileWriter().write(notes)
        val sequence = MidiSystem.getSequence(ByteArrayInputStream(bytes))
        val track = sequence.tracks.single()
        val messages = (0 until track.size()).map(track::get)

        val onTick = messages.first { (it.message as? ShortMessage)?.command == ShortMessage.NOTE_ON }.tick
        val offTick = messages.first { (it.message as? ShortMessage)?.command == ShortMessage.NOTE_OFF }.tick

        val msPerTick = 60_000.0 / MidiFileWriter.DEFAULT_BPM / sequence.resolution
        assertEquals(1000.0, onTick * msPerTick, 5.0)
        assertEquals(2000.0, offTick * msPerTick, 5.0)
    }

    @Test
    fun `an empty note list still produces a valid, parsable MIDI file`() {
        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().write(emptyList())))
        assertTrue(sequence.tracks.size == 1)
    }
}
