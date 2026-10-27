package com.example.voicetomidi.midi

import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.PitchBendEvent
import java.io.ByteArrayOutputStream

/**
 * Écrit un Standard MIDI File (SMF format 0, une seule piste) à partir d'une
 * liste de [NoteEvent] (et, en option, d'une courbe de [PitchBendEvent] pour
 * préserver le vibrato). javax.sound.midi n'existe pas sur Android : on
 * sérialise donc directement les octets du format (spec MIDI 1.0 / SMF).
 *
 * Chaque timestamp est converti en ticks indépendamment depuis son temps
 * absolu en millisecondes (pas par accumulation de deltas), ce qui évite
 * toute dérive d'arrondi au fil du morceau.
 */
class MidiFileWriter(
    private val ticksPerQuarterNote: Int = DEFAULT_TICKS_PER_QUARTER_NOTE,
    private val beatsPerMinute: Int = DEFAULT_BPM
) {

    fun write(notes: List<NoteEvent>, pitchBends: List<PitchBendEvent> = emptyList()): ByteArray =
        writeEvents(buildEventList(notes, pitchBends, channel = 0), declarePitchBendRange = pitchBends.isNotEmpty())

    /**
     * Plusieurs pistes dans un seul fichier, chacune sur son canal MIDI : elles
     * partagent le même tempo et la même horloge, donc jouent forcément
     * ensemble. Le canal 10 (index 9), réservé aux percussions en General MIDI,
     * est sauté pour les mélodies.
     */
    fun writeTogether(parts: List<List<NoteEvent>>): ByteArray {
        val events = parts
            .flatMapIndexed { i, notes -> buildEventList(notes, emptyList(), MELODIC_CHANNELS[i % MELODIC_CHANNELS.size]) }
            .sortedWith(compareBy({ it.tick }, { it.order }))
        return writeEvents(events, declarePitchBendRange = false)
    }

    private fun writeEvents(events: List<MidiEvent>, declarePitchBendRange: Boolean): ByteArray {
        val track = ByteArrayOutputStream()
        writeTempoEvent(track)
        if (declarePitchBendRange) writePitchBendRangeSetup(track)

        var lastTick = 0L
        for (event in events) {
            writeVariableLengthQuantity(track, event.tick - lastTick)
            track.write(event.bytes)
            lastTick = event.tick
        }
        writeEndOfTrack(track)

        val trackBytes = track.toByteArray()
        val output = ByteArrayOutputStream()
        writeHeaderChunk(output)
        writeTrackChunk(output, trackBytes)
        return output.toByteArray()
    }

    private data class MidiEvent(val tick: Long, val order: Int, val bytes: ByteArray)

    private fun buildEventList(notes: List<NoteEvent>, pitchBends: List<PitchBendEvent>, channel: Int): List<MidiEvent> {
        val events = mutableListOf<MidiEvent>()
        val noteOn = (NOTE_ON.toInt() or channel).toByte()
        val noteOff = (NOTE_OFF.toInt() or channel).toByte()
        val pitchBend = (PITCH_BEND.toInt() or channel).toByte()
        for (note in notes) {
            val pitch = note.midiNote.coerceIn(0, 127)
            val velocity = note.velocity.coerceIn(1, 127)
            // order=0 pour les Note Off : à tick égal, on coupe avant de rallumer,
            // ce qui évite qu'une note tenue en legato ne "saute" un instant.
            events += MidiEvent(millisToTicks(note.startTimeMs), order = 1, bytes = byteArrayOf(noteOn, pitch.toByte(), velocity.toByte()))
            events += MidiEvent(millisToTicks(note.endTimeMs), order = 0, bytes = byteArrayOf(noteOff, pitch.toByte(), 0))
        }
        for (bend in pitchBends) {
            val value = bend.value.coerceIn(0, 16383)
            events += MidiEvent(
                millisToTicks(bend.timestampMs),
                order = 2,
                bytes = byteArrayOf(pitchBend, (value and 0x7F).toByte(), ((value shr 7) and 0x7F).toByte())
            )
        }
        return events.sortedWith(compareBy({ it.tick }, { it.order }))
    }

    /** Déclare explicitement la plage de pitch bend (±2 demi-tons, standard General MIDI) via une RPN. */
    private fun writePitchBendRangeSetup(track: ByteArrayOutputStream) {
        val controlChanges = listOf(101 to 0, 100 to 0, 6 to 2, 38 to 0, 101 to 127, 100 to 127)
        for ((controller, value) in controlChanges) {
            writeVariableLengthQuantity(track, 0)
            track.write(byteArrayOf(CONTROL_CHANGE, controller.toByte(), value.toByte()))
        }
    }

    private fun millisToTicks(timeMs: Long): Long {
        val msPerQuarterNote = 60_000.0 / beatsPerMinute
        return (timeMs * ticksPerQuarterNote / msPerQuarterNote).toLong()
    }

    private fun writeTempoEvent(track: ByteArrayOutputStream) {
        val microsecondsPerQuarterNote = 60_000_000 / beatsPerMinute
        writeVariableLengthQuantity(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03))
        track.write((microsecondsPerQuarterNote shr 16) and 0xFF)
        track.write((microsecondsPerQuarterNote shr 8) and 0xFF)
        track.write(microsecondsPerQuarterNote and 0xFF)
    }

    private fun writeEndOfTrack(track: ByteArrayOutputStream) {
        writeVariableLengthQuantity(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
    }

    private fun writeHeaderChunk(output: ByteArrayOutputStream) {
        output.write("MThd".toByteArray(Charsets.US_ASCII))
        writeUInt32(output, 6)
        writeUInt16(output, 0) // format 0 : une seule piste
        writeUInt16(output, 1) // nombre de pistes
        writeUInt16(output, ticksPerQuarterNote)
    }

    private fun writeTrackChunk(output: ByteArrayOutputStream, trackBytes: ByteArray) {
        output.write("MTrk".toByteArray(Charsets.US_ASCII))
        writeUInt32(output, trackBytes.size.toLong())
        output.write(trackBytes)
    }

    private fun writeUInt16(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeUInt32(output: ByteArrayOutputStream, value: Long) {
        output.write(((value shr 24) and 0xFF).toInt())
        output.write(((value shr 16) and 0xFF).toInt())
        output.write(((value shr 8) and 0xFF).toInt())
        output.write((value and 0xFF).toInt())
    }

    /** Encodage MIDI standard d'un entier non signé sur 7 bits par octet (algorithme de la spec SMF). */
    private fun writeVariableLengthQuantity(output: ByteArrayOutputStream, value: Long) {
        var buffer = value and 0x7F
        var remaining = value shr 7
        while (remaining > 0) {
            buffer = (buffer shl 8) or ((remaining and 0x7F) or 0x80)
            remaining = remaining shr 7
        }
        while (true) {
            output.write((buffer and 0xFF).toInt())
            if (buffer and 0x80L != 0L) {
                buffer = buffer shr 8
            } else {
                break
            }
        }
    }

    companion object {
        const val DEFAULT_TICKS_PER_QUARTER_NOTE = 480
        const val DEFAULT_BPM = 120
        private const val NOTE_ON = 0x90.toByte()
        private const val NOTE_OFF = 0x80.toByte()
        private const val CONTROL_CHANGE = 0xB0.toByte()
        private const val PITCH_BEND = 0xE0.toByte()

        /** Canaux pour les mélodies : tous sauf le 10 (index 9), celui des percussions. */
        private val MELODIC_CHANNELS = (0..15).filter { it != 9 }
    }
}
