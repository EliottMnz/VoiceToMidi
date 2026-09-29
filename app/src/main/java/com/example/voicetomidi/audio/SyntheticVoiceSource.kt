package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.midiNoteToFrequencyHz
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Source audio synthétique : rejoue une courte mélodie générée (avec un léger
 * vibrato et un bruit d'imprécision simulés) à la place du micro. Traverse
 * exactement le même pipeline (fenêtrage, YIN, NoteSegmenter temps réel,
 * Viterbi à l'export) — utile pour tester sans chanter (pas de permission
 * micro nécessaire, aucun bruit produit).
 */
class SyntheticVoiceSource(
    override val sampleRate: Int = AudioCaptureManager.SAMPLE_RATE
) : AudioSource {

    @Volatile
    private var isPlaying = false

    private data class Note(val midiNote: Int?, val durationMs: Long)

    private val melody = listOf(
        Note(60, 800),  // Do4 tenu, avec vibrato
        Note(null, 300),
        Note(64, 500),  // Mi4
        Note(67, 500),  // Sol4 — legato, grand saut (teste la confirmation rapide)
        Note(null, 300),
        Note(72, 900)   // Do5 tenu, avec vibrato
    )

    override fun captureAudio(): Flow<ShortArray> = flow {
        isPlaying = true
        val chunkSize = 1024
        var phase = 0.0
        val random = Random(0)

        for (note in melody) {
            if (!isPlaying) break
            val totalSamples = (note.durationMs * sampleRate / 1000L).toInt()
            var emitted = 0

            while (emitted < totalSamples && isPlaying) {
                val samplesThisChunk = minOf(chunkSize, totalSamples - emitted)
                val buffer = ShortArray(samplesThisChunk)

                for (i in 0 until samplesThisChunk) {
                    if (note.midiNote != null) {
                        val tSec = (emitted + i).toDouble() / sampleRate
                        val vibratoCents = 12.0 * sin(2.0 * PI * 5.5 * tSec) // ~5.5Hz, ±12 cents
                        val jitterCents = (random.nextDouble() - 0.5) * 6.0
                        val freq = midiNoteToFrequencyHz(note.midiNote) *
                            Math.pow(2.0, (vibratoCents + jitterCents) / 1200.0)
                        phase += 2.0 * PI * freq / sampleRate
                        buffer[i] = (sin(phase) * 0.3 * Short.MAX_VALUE).toInt().toShort()
                    }
                }

                emit(buffer)
                emitted += samplesThisChunk
                delay(chunkSize * 1000L / sampleRate)
            }
        }
        isPlaying = false
    }

    override fun stop() {
        isPlaying = false
    }
}
