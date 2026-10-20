package com.example.voicetomidi.notes

import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyAnalyzerTest {

    private val frameMs = 10L

    /** Construit une prise synthétique, note par note, à partir de hauteurs continues (demi-tons MIDI). */
    private class Take(private val frameMs: Long) {
        val frames = mutableListOf<RawPitchFrame>()
        var t = 0L

        fun sing(durationMs: Long, pitchAt: (Long) -> Double) {
            val start = t
            while (t < start + durationMs) {
                val semitones = pitchAt(t - start)
                val freq = (440.0 * 2.0.pow((semitones - 69) / 12.0)).toFloat()
                frames += RawPitchFrame(t, freq, confidence = 0.9f, rmsAmplitude = 0.2f)
                t += frameMs
            }
        }

        fun silence(durationMs: Long) {
            val start = t
            while (t < start + durationMs) {
                frames += RawPitchFrame(t, 0f, 0f, 0f)
                t += frameMs
            }
        }
    }

    @Test
    fun `a wobbly held note with brief semitone excursions is a single note`() {
        // "doooo do# doo ré dooo" : un Do tenu 2 s avec de brefs écarts vers Do# et Ré.
        val take = Take(frameMs)
        take.sing(2000) { ms ->
            when (ms) {
                in 400L until 460L -> 61.0
                in 900L until 950L -> 62.0
                in 1300L until 1370L -> 61.1
                else -> 60.0 + 0.1 * kotlin.math.sin(ms / 30.0)
            }
        }

        val notes = MelodyAnalyzer().analyze(take.frames).notes

        assertEquals(listOf(60), notes.map { it.midiNote })
    }

    @Test
    fun `a quick legato neighbour note of equal length is kept`() {
        // "mi fa mi" en notes égales : une vraie broderie, pas une hésitation.
        val take = Take(frameMs)
        repeat(3) {
            take.sing(160) { 64.0 }
            take.sing(160) { 65.0 }
        }
        take.sing(160) { 64.0 }

        val notes = MelodyAnalyzer().analyze(take.frames).notes

        assertEquals(listOf(64, 65, 64, 65, 64, 65, 64), notes.map { it.midiNote })
    }

    @Test
    fun `a singer consistently 45 cents flat is recentred on the intended notes`() {
        val random = Random(1)
        val melody = listOf(60, 62, 64, 65, 67, 65, 64, 62, 60)
        val take = Take(frameMs)
        for (note in melody) {
            take.sing(400) { note - 0.45 + (random.nextDouble() - 0.5) * 0.3 }
            take.silence(100)
        }

        val notes = MelodyAnalyzer().analyze(take.frames).notes

        assertEquals(melody, notes.map { it.midiNote })
    }

    @Test
    fun `by default a singer drifting past half a semitone is kept as sung`() {
        val melody = List(8) { listOf(60, 64, 67) }.flatten()
        val totalMs = melody.size * 500L
        val take = Take(frameMs)
        for (note in melody) {
            take.sing(400) { note + 0.85 * take.t / totalMs }
            take.silence(100)
        }

        val notes = MelodyAnalyzer().analyze(take.frames).notes.map { it.midiNote }

        // Sans l'option "Rendre plus juste", la fin de la prise (plus d'un demi-ton au-dessus) sort un demi-ton plus haut.
        assertEquals(melody.take(12), notes.take(12))
        assertEquals(melody.takeLast(6).map { it + 1 }, notes.takeLast(6))
    }

    @Test
    fun `with pitch correction a slow drift of almost a semitone over the take is followed`() {
        val melody = List(8) { listOf(60, 64, 67) }.flatten()
        val totalMs = melody.size * 500L
        val take = Take(frameMs)
        for (note in melody) {
            take.sing(400) {
                val drift = 0.85 * take.t / totalMs // de 0 à +85 cents
                note + drift
            }
            take.silence(100)
        }

        val notes = MelodyAnalyzer(steps = AnalysisSteps.CORRECTED).analyze(take.frames).notes

        assertEquals(melody, notes.map { it.midiNote })
    }

    @Test
    fun `a note sung exactly between two semitones is resolved toward the key when correcting`() {
        // Do majeur, chanté juste, sauf la 9e note : pile entre Fa (dans la gamme) et Fa# (hors gamme).
        val melody = listOf(60, 62, 64, 65, 67, 69, 67, 65, 65, 62, 60, 64, 67, 72, 67, 64, 60)
        val take = Take(frameMs)
        val taps = mutableListOf<TapSegment>()
        for ((index, note) in melody.withIndex()) {
            val centre = if (index == 8) 65.5 else note.toDouble()
            taps += TapSegment(take.t, take.t + 400)
            take.sing(400) { centre + 0.03 * kotlin.math.sin(take.t / 40.0) }
            take.silence(100)
        }

        // Mode automatique : l'arbitrage se fait dans le 2e passage Viterbi, actif par défaut.
        // Mode bouton : il fait partie de l'option "Rendre plus juste".
        val automatic = MelodyAnalyzer().analyze(take.frames)
        val withButton = MelodyAnalyzer(steps = AnalysisSteps.CORRECTED).analyze(take.frames, taps)

        // Pile au milieu, Viterbi n'a quasiment aucune préférence : Fa ou Fa#, les autres notes intactes.
        val autoNotes = automatic.notes.map { it.midiNote }
        assertTrue(autoNotes[8] in setOf(65, 66))
        assertEquals(melody.filterIndexed { i, _ -> i != 8 }, autoNotes.filterIndexed { i, _ -> i != 8 })
        assertEquals(melody, withButton.notes.map { it.midiNote })
        assertEquals(0, automatic.key?.tonicPitchClass)
    }

    @Test
    fun `a precise singer keeps an intentional out-of-key note`() {
        val melody = listOf(60, 62, 64, 65, 67, 69, 67, 66, 67, 65, 64, 62, 60, 64, 67, 72, 67, 64, 60)
        val take = Take(frameMs)
        for (note in melody) {
            take.sing(400) { note + 0.03 * kotlin.math.sin(take.t / 40.0) }
            take.silence(100)
        }

        val notes = MelodyAnalyzer().analyze(take.frames).notes

        assertEquals(melody, notes.map { it.midiNote })
    }

    @Test
    fun `taps separate a repeated note sung legato without any silence`() {
        val take = Take(frameMs)
        take.sing(1500) { 65.0 }
        val taps = listOf(TapSegment(0, 480), TapSegment(520, 980), TapSegment(1020, 1500))

        val notes = MelodyAnalyzer().analyze(take.frames, taps).notes

        assertEquals(listOf(65, 65, 65), notes.map { it.midiNote })
    }

    @Test
    fun `a late finger press is snapped back to the real start of the sound`() {
        val take = Take(frameMs)
        take.silence(300)
        take.sing(600) { 64.0 } // le son commence à 300 ms
        take.silence(300)
        val taps = listOf(TapSegment(410, 880)) // le doigt arrive 110 ms trop tard

        val notes = MelodyAnalyzer().analyze(take.frames, taps).notes

        assertEquals(1, notes.size)
        assertEquals(64, notes[0].midiNote)
        assertEquals(300L, notes[0].startTimeMs)
        assertEquals(900L, notes[0].endTimeMs)
    }

    @Test
    fun `a press without any singing produces no note`() {
        val take = Take(frameMs)
        take.sing(400) { 60.0 }
        take.silence(600)
        val taps = listOf(TapSegment(0, 400), TapSegment(600, 900))

        val notes = MelodyAnalyzer().analyze(take.frames, taps).notes

        assertEquals(listOf(60), notes.map { it.midiNote })
    }

    @Test
    fun `glides between tapped notes do not change the notes`() {
        val take = Take(frameMs)
        take.sing(500) { 60.0 }
        take.sing(120) { ms -> 60.0 + 4.0 * ms / 120.0 } // glissé Do -> Mi
        take.sing(500) { 64.0 }
        val taps = listOf(TapSegment(0, 560), TapSegment(580, 1120)) // appuis imprécis qui mordent sur le glissé

        val notes = MelodyAnalyzer().analyze(take.frames, taps).notes

        assertEquals(listOf(60, 64), notes.map { it.midiNote })
    }

    @Test
    fun `vibrato frames are centred on the final note`() {
        val take = Take(frameMs)
        take.sing(1000) { 60.0 - 0.35 } // tout le Do chanté 35 cents trop bas

        val analysis = MelodyAnalyzer().analyze(take.frames)

        assertNotNull(analysis.key)
        val voiced = analysis.vibratoFrames.filter { it.frequencyHz > 0f }
        assertTrue(voiced.all { abs(centsBetween(it.frequencyHz, 60)) < 5.0 })
    }

    @Test
    fun `an empty take yields nothing`() {
        val analysis = MelodyAnalyzer().analyze(emptyList())
        assertTrue(analysis.notes.isEmpty())
        assertEquals(null, analysis.key)
    }

    @Test
    fun `a faint hum with button presses gives no note and reports the input as too quiet`() {
        // Micro inactif de l'émulateur : bourdonnement à 100 Hz vers -78 dBFS, que YIN
        // détecte avec une bonne confiance. Avant, chaque appui devenait un "Lab2".
        val hum = (0L until 6000L step frameMs).map { RawPitchFrame(it, 100f, 0.9f, 0.00013f) }
        val taps = listOf(TapSegment(1000, 2000), TapSegment(3000, 4000))

        val analysis = MelodyAnalyzer().analyze(hum, taps)

        assertTrue(analysis.notes.isEmpty())
        assertTrue(analysis.inputTooQuiet)
    }

    @Test
    fun `background noise much quieter than the voice does not become a note`() {
        val take = Take(frameMs)
        take.sing(800) { 60.0 }
        // Bruit de fond voisé (ventilateur...) 30 fois plus faible que la voix.
        repeat(80) { take.frames += RawPitchFrame(take.t, 104f, 0.9f, 0.006f); take.t += frameMs }
        take.sing(800) { 64.0 }

        val notes = MelodyAnalyzer().analyze(take.frames).notes

        assertEquals(listOf(60, 64), notes.map { it.midiNote })
    }
}
