package com.example.voicetomidi.midi

import com.example.voicetomidi.notes.NoteEvent
import java.io.ByteArrayOutputStream

/**
 * Écrit un Standard MIDI File (SMF format 0, une seule piste) à partir d'une
 * liste de [NoteEvent]. javax.sound.midi n'existe pas sur Android : on
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

    fun write(notes: List<NoteEvent>): ByteArray {
        val track = ByteArrayOutputStream()
        writeTempoEvent(track)

        var lastTick = 0L
        for (event in buildEventList(notes)) {
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

    private fun buildEventList(notes: List<NoteEvent>): List<MidiEvent> {
        val events = mutableListOf<MidiEvent>()
        for (note in notes) {
            val pitch = note.midiNote.coerceIn(0, 127)
            val velocity = note.velocity.coerceIn(1, 127)
            // order=0 pour les Note Off : à tick égal, on coupe avant de rallumer,
            // ce qui évite qu'une note tenue en legato ne "saute" un instant.
            events += MidiEvent(millisToTicks(note.startTimeMs), order = 1, bytes = byteArrayOf(NOTE_ON, pitch.toByte(), velocity.toByte()))
            events += MidiEvent(millisToTicks(note.endTimeMs), order = 0, bytes = byteArrayOf(NOTE_OFF, pitch.toByte(), 0))
        }
        return events.sortedWith(compareBy({ it.tick }, { it.order }))
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
    }
}
