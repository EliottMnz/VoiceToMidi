package com.example.voicetomidi.midi

import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.PitchBendEvent
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

    @Test
    fun `pitch bend events are written and decoded back with the correct 14-bit value`() {
        val notes = listOf(NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 500, velocity = 90))
        val bends = listOf(PitchBendEvent(timestampMs = 250, value = 10240))

        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().write(notes, bends)))
        val messages = (0 until sequence.tracks.single().size()).map { sequence.tracks.single().get(it) }

        val bendMessages = messages.mapNotNull { it.message as? ShortMessage }
            .filter { it.command == ShortMessage.PITCH_BEND }

        assertEquals(1, bendMessages.size)
        val decoded = bendMessages[0].data1 or (bendMessages[0].data2 shl 7)
        assertEquals(10240, decoded)
    }

    @Test
    fun `pitch bend range is declared via RPN when pitch bends are present`() {
        val notes = listOf(NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 500, velocity = 90))
        val bends = listOf(PitchBendEvent(timestampMs = 0, value = 8192))

        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().write(notes, bends)))
        val controlChanges = (0 until sequence.tracks.single().size())
            .map { sequence.tracks.single().get(it) }
            .mapNotNull { it.message as? ShortMessage }
            .filter { it.command == ShortMessage.CONTROL_CHANGE }

        // RPN "pitch bend sensitivity" (101=0, 100=0) avec 2 demi-tons (CC6=2), puis RPN null (101=127,100=127).
        assertEquals(listOf(101, 100, 6, 38, 101, 100), controlChanges.map { it.data1 })
        assertEquals(listOf(0, 0, 2, 0, 127, 127), controlChanges.map { it.data2 })
    }

    @Test
    fun `tracks written together land on separate channels at the same time`() {
        val melodyA = listOf(NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 500, velocity = 90))
        val melodyB = listOf(NoteEvent(midiNote = 67, startTimeMs = 0, endTimeMs = 500, velocity = 90))
        val melodies = (0 until 10).map { listOf(NoteEvent(midiNote = 60 + it, startTimeMs = 0, endTimeMs = 100, velocity = 90)) }

        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().writeTogether(listOf(melodyA, melodyB))))
        val noteOns = (0 until sequence.tracks.single().size())
            .map { sequence.tracks.single().get(it) }
            .filter { (it.message as? ShortMessage)?.command == ShortMessage.NOTE_ON }

        assertEquals(listOf(0, 1), noteOns.map { (it.message as ShortMessage).channel }.sorted())
        assertTrue("les deux pistes démarrent ensemble", noteOns.all { it.tick == 0L })

        // Le canal 10 (index 9) est réservé aux percussions : une 10e mélodie passe au canal suivant.
        val tenTracks = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().writeTogether(melodies)))
        val channels = (0 until tenTracks.tracks.single().size())
            .mapNotNull { tenTracks.tracks.single().get(it).message as? ShortMessage }
            .filter { it.command == ShortMessage.NOTE_ON }
            .map { it.channel }
        assertTrue(9 !in channels)
        assertEquals(10, channels.toSet().size)
    }

    @Test
    fun `no pitch bend or RPN messages are written when no pitch bends are given`() {
        val notes = listOf(NoteEvent(midiNote = 60, startTimeMs = 0, endTimeMs = 500, velocity = 90))

        val sequence = MidiSystem.getSequence(ByteArrayInputStream(MidiFileWriter().write(notes)))
        val messages = (0 until sequence.tracks.single().size())
            .map { sequence.tracks.single().get(it) }
            .mapNotNull { it.message as? ShortMessage }

        assertTrue(messages.none { it.command == ShortMessage.PITCH_BEND || it.command == ShortMessage.CONTROL_CHANGE })
    }
}
