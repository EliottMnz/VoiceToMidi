package com.example.voicetomidi.audio

import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.TapSegment
import com.example.voicetomidi.pipeline.VoiceToMidiPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Le clic du métronome, capté par le micro, ne doit pas devenir une note.
 * Faute de pouvoir le vérifier sur l'appareil, on rejoue la situation : le
 * même clic, ajouté au son d'une pièce, à différents volumes, et "coloré"
 * comme par un petit haut-parleur (résonance qui le fait sonner plus tonal,
 * le pire cas pour la détection de hauteur).
 */
class MetronomeClickTest {

    private val sampleRate = 44100

    private class Samples(private val samples: ShortArray, override val sampleRate: Int) : AudioSource {
        override fun captureAudio(): Flow<ShortArray> = flow {
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + 1024, samples.size)
                emit(samples.copyOfRange(i, end))
                i = end
            }
        }

        override fun stop() = Unit
    }

    private fun frames(signal: FloatArray): List<RawPitchFrame> = runBlocking {
        val pcm = ShortArray(signal.size) { (signal[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        VoiceToMidiPipeline(audioSource = Samples(pcm, sampleRate)).run().toList()
            .map { RawPitchFrame(it.timestampMs, it.pitch.frequencyHz, it.pitch.confidence, it.rmsAmplitude) }
    }

    /** Résonateur du 2e ordre : imite un petit haut-parleur qui "chante" à [hz] (null = clic brut). */
    private fun speaker(click: FloatArray, hz: Double?): FloatArray {
        if (hz == null) return click
        val r = exp(-PI * hz / 5 / sampleRate)
        val a1 = 2 * r * cos(2 * PI * hz / sampleRate)
        val a2 = -r * r
        val out = FloatArray(click.size + sampleRate / 20)
        var y1 = 0.0
        var y2 = 0.0
        var peak = 1e-9
        val raw = DoubleArray(out.size)
        for (i in out.indices) {
            val y = click.getOrElse(i) { 0f } + a1 * y1 + a2 * y2
            raw[i] = y
            peak = maxOf(peak, kotlin.math.abs(y))
            y2 = y1
            y1 = y
        }
        val target = click.maxOf { kotlin.math.abs(it) }
        for (i in out.indices) out[i] = (raw[i] / peak * target).toFloat()
        return out
    }

    /** Clics à [bpm] ajoutés à [base], au volume de crête [level]. */
    private fun withClicks(base: FloatArray, bpm: Int, level: Float, speakerHz: Double?): FloatArray {
        val click = speaker(metronomeClick(sampleRate, level), speakerHz)
        val out = base.copyOf()
        val beat = sampleRate * 60 / bpm
        var start = 0
        while (start < out.size) {
            for (i in click.indices) if (start + i < out.size) out[start + i] += click[i]
            start += beat
        }
        return out
    }

    private fun roomNoise(seconds: Int): FloatArray {
        val random = Random(7)
        return FloatArray(sampleRate * seconds) { (random.nextFloat() * 2 - 1) * 0.01f }
    }

    private val speakers = listOf(null, 800.0, 1200.0, 2500.0, 4000.0)

    @Test
    fun lesClicsSeulsNeDonnentAucuneNote() {
        for (level in listOf(0.05f, 0.2f, 0.6f)) for (hz in speakers) {
            val f = frames(withClicks(roomNoise(8), bpm = 100, level = level, speakerHz = hz))
            val notes = MelodyAnalyzer().analyze(f).notes
            println("CLIC volume $level, haut-parleur ${hz ?: "brut"} : ${notes.size} notes, ${f.count { it.frequencyHz > 0f }} frames avec hauteur sur ${f.size}")
            assertEquals("clics pris pour des notes (volume $level, haut-parleur $hz)", 0, notes.size)
        }
    }

    /** Une voix tenue (Do4 avec harmoniques) : le métronome ne doit ni ajouter ni changer de note. */
    @Test
    fun lesClicsNeChangentPasUneVoix() {
        val voice = roomNoise(6).also { s ->
            val notes = listOf(60, 62, 64, 60)
            for ((k, midi) in notes.withIndex()) {
                val hz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                for (i in (k * 1.4 * sampleRate).toInt() until ((k * 1.4 + 1.2) * sampleRate).toInt()) {
                    val t = i.toDouble() / sampleRate
                    s[i] += (0.15 * sin(2 * PI * hz * t) + 0.07 * sin(4 * PI * hz * t) + 0.03 * sin(6 * PI * hz * t)).toFloat()
                }
            }
        }
        val reference = MelodyAnalyzer().analyze(frames(voice)).notes.map { it.midiNote }
        for (level in listOf(0.05f, 0.2f, 0.6f)) for (hz in speakers) {
            val notes = MelodyAnalyzer().analyze(frames(withClicks(voice, bpm = 100, level = level, speakerHz = hz))).notes.map { it.midiNote }
            println("CLIC voix, volume $level, haut-parleur ${hz ?: "brut"} : $notes (sans clic : $reference)")
            assertEquals("le métronome change les notes (volume $level, haut-parleur $hz)", reference, notes)
        }
    }

    /** Vraies prises (variable PRISES_DIR, dossier des prise_*.wav/.txt de la tablette, hors dépôt). */
    @Test
    fun vraiesPrisesAvecClics() {
        val dir = System.getenv("PRISES_DIR")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        dir!!.listFiles { f -> f.name.endsWith(".wav") }!!.sorted().forEach { wav ->
            val bytes = wav.readBytes()
            val pcm = ShortArray((bytes.size - 44) / 2).also { ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
            val voice = FloatArray(pcm.size) { pcm[it] / 32768f }
            val taps = File(wav.path.replace(".wav", ".txt")).readLines().filter { it.startsWith("tap,") }
                .map { it.split(",").let { p -> TapSegment(p[1].toLong(), p[2].toLong()) } }
            val reference = MelodyAnalyzer().analyze(frames(voice), taps).notes.map { it.midiNote }
            for (level in listOf(0.2f, 0.6f)) for (hz in listOf(null, 1200.0, 2500.0)) {
                val notes = MelodyAnalyzer().analyze(frames(withClicks(voice, 100, level, hz)), taps).notes.map { it.midiNote }
                changed += diff(reference, notes)
                total += reference.size
                println("CLIC ${wav.name} volume $level hp ${hz ?: "brut"} : ${diff(reference, notes)} notes changées sur ${reference.size}")
            }
        }
        // Mesuré le 20/10/2026 : 58 notes changées sur 864 (~7 %), jamais de note ajoutée par
        // un clic seul. Rendre muettes les frames qui touchent un clic faisait pire (126) :
        // on y perdait plus de voix que le clic n'en abîme. Avec un casque : aucun effet.
        println("CLIC TOTAL : $changed notes changées sur $total")
    }

    private var changed = 0
    private var total = 0

    /** Notes de [a] absentes de [b] (et inversement), par plus longue sous-suite commune. */
    private fun diff(a: List<Int>, b: List<Int>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) for (j in b.indices) t[i + 1][j + 1] = if (a[i] == b[j]) t[i][j] + 1 else maxOf(t[i][j + 1], t[i + 1][j])
        return maxOf(a.size, b.size) - t[a.size][b.size]
    }
}
