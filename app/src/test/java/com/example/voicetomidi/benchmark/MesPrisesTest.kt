package com.example.voicetomidi.benchmark

import com.example.voicetomidi.notes.AnalysisSteps
import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.Scale
import com.example.voicetomidi.notes.ScaleConstraint
import com.example.voicetomidi.notes.TapSegment
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prises réelles de l'auteur (tablette Samsung, pièce bruyante), sur des
 * chansons connues : la seule mesure de *l'intention* dont on dispose
 * (vocadito note ce qui est chanté, pas ce que le chanteur voulait).
 *
 * Seules les mesures (frames YIN, appuis) sont dans le dépôt, pas le son.
 * Une note est juste si la *mélodie* l'est : on compare les notes à la
 * mélodie attendue à la meilleure transposition près, en alignant les deux
 * suites (plus longue sous-suite commune), donc une note en trop ou en moins
 * ne décale pas tout le reste.
 */
class MesPrisesTest {

    private class Take(
        val file: String,
        /** Mélodie attendue, en demi-tons (l'origine est arbitraire). */
        val melody: List<Int>,
        /** Toniques plausibles de ce qui a été chanté (0 = Do), voix entre deux notes oblige. */
        val sungTonics: List<Int>
    )

    private val takes = listOf(
        // 10 appuis : le 3e "do" de "Au clair de la lune" n'a pas eu d'appui à lui.
        Take("au_clair_de_la_lune_1", listOf(0, 0, 2, 4, 2, 0, 4, 2, 2, 0), sungTonics = listOf(10, 11)),
        Take("au_clair_de_la_lune_2", listOf(0, 0, 2, 4, 2, 0, 4, 2, 2, 0), sungTonics = listOf(10, 11)),
        // "Joyeux an-" en un seul appui, puis "-ni-ver-saire..." ; tonique = sol + 5.
        Take("joyeux_anniversaire", listOf(7, 9, 7, 12, 11, 7, 7, 9, 7, 14, 12), sungTonics = listOf(3, 4)),
        Take("frere_jacques", listOf(0, 2, 4, 0, 0, 2, 4, 0, 4, 5, 7, 4, 5, 7), sungTonics = listOf(11, 0)),
    )

    private fun load(name: String): Pair<List<RawPitchFrame>, List<TapSegment>> {
        val frames = mutableListOf<RawPitchFrame>()
        val taps = mutableListOf<TapSegment>()
        javaClass.getResourceAsStream("/prises/$name.txt")!!.bufferedReader().forEachLine { line ->
            val p = line.split(",")
            when (p[0]) {
                "frame" -> frames += RawPitchFrame(p[1].toLong(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                "tap" -> taps += TapSegment(p[1].toLong(), p[2].toLong())
            }
        }
        return frames to taps
    }

    /** Notes justes : plus longue sous-suite commune avec la mélodie, à la meilleure transposition. */
    private fun correctNotes(notes: List<Int>, melody: List<Int>): Int =
        (-60..120).maxOf { shift -> lcs(notes.map { it - shift }, melody) }

    private fun lcs(a: List<Int>, b: List<Int>): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) for (j in b.indices) {
            table[i + 1][j + 1] = if (a[i] == b[j]) table[i][j] + 1 else maxOf(table[i][j + 1], table[i + 1][j])
        }
        return table[a.size][b.size]
    }

    private fun score(analyzer: (Take) -> MelodyAnalyzer): Int = takes.sumOf { take ->
        val (frames, taps) = load(take.file)
        correctNotes(analyzer(take).analyze(frames, taps).notes.map { it.midiNote }, take.melody)
    }

    private fun major(tonic: Int) = ScaleConstraint(Scale.MAJOR, tonic)

    @Test
    fun mesPrises() {
        val total = takes.sumOf { it.melody.size }
        val asSung = score { MelodyAnalyzer() }
        val corrected = score { MelodyAnalyzer(steps = AnalysisSteps.CORRECTED) }
        // Gamme choisie par l'utilisateur : celle où il chante vraiment (la moins bonne des
        // deux toniques plausibles, pour ne pas se flatter)...
        val sungKey = takes.sumOf { take ->
            val (frames, taps) = load(take.file)
            take.sungTonics.minOf { tonic ->
                correctNotes(MelodyAnalyzer(scale = major(tonic)).analyze(frames, taps).notes.map { it.midiNote }, take.melody)
            }
        }
        // ... et la meilleure des 12 gammes majeures, qui borne ce que la gamme peut apporter.
        val bestKey = takes.sumOf { take ->
            val (frames, taps) = load(take.file)
            (0..11).maxOf { tonic ->
                correctNotes(MelodyAnalyzer(scale = major(tonic)).analyze(frames, taps).notes.map { it.midiNote }, take.melody)
            }
        }
        println("PRISES tel que chanté : $asSung / $total")
        println("PRISES plus juste     : $corrected / $total")
        println("PRISES gamme chantée  : $sungKey / $total")
        println("PRISES meilleure gamme: $bestKey / $total")
        for (take in takes) {
            val (frames, taps) = load(take.file)
            val notes = MelodyAnalyzer().analyze(frames, taps).notes.map { it.midiNote }
            println("PRISES   ${take.file} : ${correctNotes(notes, take.melody)} / ${take.melody.size}")
            println("PRISES     par tonique 0..11 : " + (0..11).joinToString(" ") { t ->
                "$t:" + correctNotes(MelodyAnalyzer(scale = major(t)).analyze(frames, taps).notes.map { it.midiNote }, take.melody)
            } + "   notes : " + notes.joinToString(" "))
        }
        assertTrue("régression sur les prises de l'auteur : $asSung", asSung >= BASELINE_AS_SUNG)
    }

    /** 40 s de fredonnement, 3 appuis au début : le chant qui suit ne doit pas disparaître. */
    @Test
    fun chantSansAppuiGarde() {
        val (frames, taps) = load("fredonnement_long_peu_d_appuis")
        val notes = MelodyAnalyzer().analyze(frames, taps).notes
        val afterTaps = notes.count { it.startTimeMs in 11_000L..36_000L }
        println("PRISES fredonnement : ${notes.size} notes, dont $afterTaps chantées sans appui")
        assertTrue("le chant sans appui a été perdu ($afterTaps notes)", afterTaps >= 10)
    }

    private companion object {
        /** Score mesuré le 20/10/2026 ; à remonter quand l'algorithme progresse. */
        const val BASELINE_AS_SUNG = 26
    }
}
