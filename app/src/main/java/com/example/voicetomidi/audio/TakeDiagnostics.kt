package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.TapSegment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Garde le son brut d'une prise pour pouvoir l'analyser après coup (diagnostic) :
 * quand un test réel donne un résultat étrange, le WAV + les mesures permettent de
 * savoir si le problème vient du micro (signal faible, bruit) ou de l'algorithme.
 */
class TakeDiagnostics(private val sampleRate: Int) {

    private val pcm = ByteArrayOutputStream()

    /** Appelé depuis le thread de capture, pour chaque paquet lu au micro. */
    @Synchronized
    fun append(chunk: ShortArray) {
        for (sample in chunk) {
            pcm.write(sample.toInt() and 0xFF)
            pcm.write((sample.toInt() shr 8) and 0xFF)
        }
    }

    /**
     * Écrit `prise_<horodatage>.wav` (son brut) et `prise_<horodatage>.txt`
     * (appuis, notes finales, mesures frame par frame) dans [directory].
     */
    fun save(
        directory: File,
        frames: List<RawPitchFrame>,
        taps: List<TapSegment>,
        notes: List<NoteEvent>
    ): File {
        directory.mkdirs()
        val stamp = System.currentTimeMillis()
        val bytes = synchronized(this) { pcm.toByteArray() }
        writeWav(File(directory, "prise_$stamp.wav"), bytes)

        val report = File(directory, "prise_$stamp.txt")
        report.bufferedWriter().use { out ->
            out.write("# taps (startMs,endMs)\n")
            taps.forEach { out.write("tap,${it.startMs},${it.endMs}\n") }
            out.write("# notes (midi,startMs,endMs,velocity)\n")
            notes.forEach { out.write("note,${it.midiNote},${it.startTimeMs},${it.endTimeMs},${it.velocity}\n") }
            out.write("# frames (timestampMs,frequencyHz,confidence,rms)\n")
            frames.forEach {
                out.write(String.format(Locale.US, "frame,%d,%.2f,%.3f,%.5f\n", it.timestampMs, it.frequencyHz, it.confidence, it.rmsAmplitude))
            }
        }
        deleteOldTakes(directory)
        return report
    }

    /** Ne garde que les [MAX_KEPT_TAKES] dernières prises (~5 Mo par minute de son). */
    private fun deleteOldTakes(directory: File) {
        val takes = directory.listFiles { f -> f.name.startsWith("prise_") }.orEmpty()
            .groupBy { it.nameWithoutExtension }
            .entries.sortedByDescending { it.key }
        takes.drop(MAX_KEPT_TAKES).forEach { entry -> entry.value.forEach { it.delete() } }
    }

    private fun writeWav(file: File, pcmBytes: ByteArray) {
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun int32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun int16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); int32(36 + pcmBytes.size); out.writeBytes("WAVE")
            out.writeBytes("fmt "); int32(16); int16(1); int16(1)
            int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
            out.writeBytes("data"); int32(pcmBytes.size)
            out.write(pcmBytes)
        }
    }

    private companion object {
        const val MAX_KEPT_TAKES = 10
    }
}
