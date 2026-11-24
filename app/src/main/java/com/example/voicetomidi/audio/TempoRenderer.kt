package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.PitchBendEvent
import com.example.voicetomidi.project.DEFAULT_PROJECT_BPM
import com.example.voicetomidi.project.MIN_PROJECT_BPM
import com.example.voicetomidi.project.MAX_PROJECT_BPM
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Petit synthé polyphonique : l'horloge des notes suit le BPM, les oscillateurs
 * suivent les échantillons audio. Accélérer ne transpose donc jamais les notes.
 * Les notes reçues sont exprimées au tempo de référence de 120 BPM.
 */
class TempoRenderer(notes: List<NoteEvent>, fromMs: Double = 0.0, bends: List<PitchBendEvent> = emptyList()) {
    private val notes = notes.sortedBy { it.startTimeMs }
    private val bends = bends.sortedBy { it.timestampMs }
    private var nextBend = 0
    private var bend = 1.0
    private var nextNote = 0
    private val voices = mutableListOf<Voice>()
    var positionMs: Double = fromMs
        private set
    val durationMs: Double = (notes.maxOfOrNull { it.endTimeMs } ?: 0L).toDouble()
    @Volatile var bpm: Int = DEFAULT_PROJECT_BPM
    val finished: Boolean get() = nextNote == notes.size && voices.isEmpty()

    init {
        // Un seek au milieu d'une note la rejoue, sans rejouer toutes les attaques précédentes.
        while (nextNote < this.notes.size && this.notes[nextNote].startTimeMs < fromMs) {
            val note = this.notes[nextNote++]
            if (note.endTimeMs > fromMs) voices += Voice(note)
        }
    }

    /** Produit un bloc PCM mono. Le tempo est lu une fois par bloc (256 échantillons). */
    fun render(output: ShortArray) {
        val step = 1000.0 / SAMPLE_RATE * bpm.coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM) / DEFAULT_PROJECT_BPM
        for (i in output.indices) {
            while (nextNote < notes.size && notes[nextNote].startTimeMs <= positionMs) {
                voices += Voice(notes[nextNote++])
            }
            while (nextBend < bends.size && bends[nextBend].timestampMs <= positionMs) {
                bend = 2.0.pow(((bends[nextBend++].value - 8192) / 8192.0 * 2) / 12)
            }
            var sample = 0.0
            val iterator = voices.iterator()
            while (iterator.hasNext()) {
                val voice = iterator.next()
                sample += voice.sample(positionMs, bend)
                if (voice.done) iterator.remove()
            }
            output[i] = (tanh(sample * 0.22) * Short.MAX_VALUE).toInt().toShort()
            positionMs += step
        }
    }

    private class Voice(private val note: NoteEvent) {
        private val increment = 440.0 * 2.0.pow((note.midiNote - 69) / 12.0) * TABLE_SIZE / SAMPLE_RATE
        private var phase = 0.0
        private var age = 0
        private var release = -1
        val done: Boolean get() = release >= RELEASE_SAMPLES

        fun sample(positionMs: Double, bend: Double): Double {
            if (positionMs >= note.endTimeMs && release < 0) release = 0
            val attack = (age / (SAMPLE_RATE * 0.006)).coerceAtMost(1.0)
            val decay = 0.25 + 0.75 * exp(-age / (SAMPLE_RATE * 0.7))
            val envelope = if (release >= 0) (1.0 - release++.toDouble() / RELEASE_SAMPLES).coerceAtLeast(0.0) else 1.0
            val sample = table[phase.toInt() % TABLE_SIZE] * attack * decay * envelope * note.velocity / 127.0
            phase = (phase + increment * bend) % TABLE_SIZE
            age++
            return sample
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val TABLE_SIZE = 4096
        private const val RELEASE_SAMPLES = 2205
        // Timbre doux, proche d'un clavier ; aucun échantillon ni dépendance externe.
        private val table = DoubleArray(TABLE_SIZE) { i ->
            val phase = 2 * PI * i / TABLE_SIZE
            (sin(phase) + 0.3 * sin(2 * phase) + 0.12 * sin(3 * phase)) / 1.42
        }
    }
}
