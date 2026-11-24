package com.example.voicetomidi.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.PitchBendEvent
import java.util.concurrent.ConcurrentLinkedQueue

/** Lecture en flux : le changement de tempo ne recharge aucun fichier et conserve la pulsation. */
class TempoPlayer(notes: List<NoteEvent>, fromMs: Double, bpm: Int, bends: List<PitchBendEvent> = emptyList()) {
    private val renderer = TempoRenderer(notes, fromMs, bends).also { it.bpm = bpm }
    private val format = AudioFormat.Builder()
        .setSampleRate(TempoRenderer.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(2048, AudioTrack.getMinBufferSize(TempoRenderer.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private data class Block(val frame: Long, val positionMs: Double, val step: Double)
    private val blocks = ConcurrentLinkedQueue<Block>()
    private var anchor = Block(0, fromMs, 0.0)
    @Volatile private var running = false
    @Volatile private var released = false
    @Volatile var failure: String? = null
        private set
    @Volatile var completed: Boolean = false
        private set

    /** Position entendue, corrigée du tampon audio, dans le tempo de référence. */
    fun positionMs(): Double {
        if (released) return anchor.positionMs
        val frame = try { track.playbackHeadPosition.toLong() and 0xffffffffL } catch (_: IllegalStateException) { return anchor.positionMs }
        while (blocks.peek()?.frame?.let { it <= frame } == true) anchor = blocks.poll() ?: anchor
        return (anchor.positionMs + (frame - anchor.frame).coerceAtLeast(0) * anchor.step).coerceAtMost(renderer.durationMs)
    }

    fun setTempo(bpm: Int) { renderer.bpm = bpm }

    fun start() {
        running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                track.play()
                val buffer = ShortArray(256)
                var framesWritten = 0L
                while (running && !renderer.finished) {
                    val start = renderer.positionMs
                    renderer.render(buffer)
                    blocks.add(Block(framesWritten, start, (renderer.positionMs - start) / buffer.size))
                    var offset = 0
                    while (running && offset < buffer.size) {
                        val written = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written >= 0) { "Sortie audio indisponible ($written)" }
                        if (written == 0 && !running) break
                        offset += written
                        framesWritten += written
                    }
                }
                // Attendre que le dernier bloc soit entendu avant d'annoncer la fin.
                while (running && (track.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten) Thread.sleep(5)
                if (running) completed = true
            } catch (e: Exception) {
                if (running) failure = e.message ?: "Sortie audio indisponible"
            } finally {
                synchronized(track) {
                    released = true
                    track.release()
                }
            }
        }, "Lecture-tempo").start()
    }

    fun stop() {
        running = false
        synchronized(track) {
            if (!released) {
                track.pause()
                track.flush()
            }
        }
    }
}
