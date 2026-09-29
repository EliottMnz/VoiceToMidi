package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.midiNoteToFrequencyHz
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Source audio synthétique : rejoue un [DemoScenario] (avec un vibrato et un
 * bruit d'imprécision simulés) à la place du micro. Traverse exactement le
 * même pipeline (fenêtrage, YIN, NoteSegmenter temps réel, Viterbi à
 * l'export) — utile pour tester sans channer ou sans permission micro.
 *
 * Les timestamps du pipeline sont calculés à partir du nombre d'échantillons
 * émis (pas de l'horloge murale) : accélérer [speedMultiplier] ne fausse donc
 * aucun seuil de temps du NoteSegmenter ou du ViterbiNoteSegmenter, ça rend
 * juste la lecture plus rapide à l'écran.
 */
class SyntheticVoiceSource(
    private val scenario: DemoScenario = DemoScenario.STANDARD,
    private val speedMultiplier: Float = 3f,
    override val sampleRate: Int = AudioCaptureManager.SAMPLE_RATE
) : AudioSource {

    @Volatile
    private var isPlaying = false

    override fun captureAudio(): Flow<ShortArray> = flow {
        isPlaying = true
        val chunkSize = 1024
        var phase = 0.0
        val random = Random(0)
        val drift = scenario.driftCents

        for (note in scenario.notes) {
            if (!isPlaying) break
            val totalSamples = (note.durationMs * sampleRate / 1000L).toInt()
            var emitted = 0

            while (emitted < totalSamples && isPlaying) {
                val samplesThisChunk = minOf(chunkSize, totalSamples - emitted)
                val buffer = ShortArray(samplesThisChunk)

                for (i in 0 until samplesThisChunk) {
                    if (note.midiNote != null) {
                        val tSec = (emitted + i).toDouble() / sampleRate
                        val vibratoCents = (drift * 0.6) * sin(2.0 * PI * 5.5 * tSec) // ~5.5Hz
                        val jitterCents = (random.nextDouble() - 0.5) * drift * 0.4
                        val freq = midiNoteToFrequencyHz(note.midiNote) *
                            Math.pow(2.0, (vibratoCents + jitterCents) / 1200.0)
                        phase += 2.0 * PI * freq / sampleRate
                        buffer[i] = (sin(phase) * 0.3 * Short.MAX_VALUE).toInt().toShort()
                    }
                }

                emit(buffer)
                emitted += samplesThisChunk
                val realtimeDelayMs = chunkSize * 1000L / sampleRate
                delay((realtimeDelayMs / speedMultiplier).toLong().coerceAtLeast(1))
            }
        }
        isPlaying = false
    }

    override fun stop() {
        isPlaying = false
    }
}
