package com.example.voicetomidi.benchmark

import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.NoteEvent
import java.io.File
import java.util.Locale
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exporte, pour les plus longs extraits de vocadito, de quoi comparer à
 * l'oreille dans un DAW (FL Studio...) : la voix d'origine, le MIDI produit
 * par l'appli et le MIDI des annotations humaines.
 *
 * Ignoré sans VOCADITO_DIR et EXPORT_DIR :
 *
 *   VOCADITO_DIR=... EXPORT_DIR=... ./gradlew testDebugUnitTest --tests '*VocaditoMidiExport*'
 */
class VocaditoMidiExport {

    @Test
    fun export() {
        val dir = System.getenv("VOCADITO_DIR")?.let(::File)
        val out = System.getenv("EXPORT_DIR")?.let(::File)
        assumeTrue("VOCADITO_DIR / EXPORT_DIR non définis : export ignoré", dir != null && dir.isDirectory && out != null)
        out!!.mkdirs()

        val longest = (1..40)
            .map { File(dir, "Audio/vocadito_$it.wav") }
            .filter { it.exists() }
            .sortedByDescending { AudioSystem.getAudioFileFormat(it).frameLength }
            .take(EXCERPT_COUNT)

        val summary = StringBuilder("Extraits vocadito (CC BY 4.0, https://zenodo.org/records/5578807)\n")
        summary.append("Tempo des MIDI : ${MidiFileWriter.DEFAULT_BPM} BPM (régler le projet FL Studio sur ce tempo pour que tout s'aligne).\n\n")

        val benchmark = VocaditoBenchmark()
        val analyzeFrames = VocaditoBenchmark::class.java.getDeclaredMethod("analyzeFrames", File::class.java).apply { isAccessible = true }

        val html = StringBuilder()
        for (wav in longest) {
            val name = wav.nameWithoutExtension
            @Suppress("UNCHECKED_CAST")
            val frames = analyzeFrames.invoke(benchmark, wav) as List<com.example.voicetomidi.notes.RawPitchFrame>
            val analysis = MelodyAnalyzer().analyze(frames)
            val reference = File(dir, "Annotations/Notes/${name}_notesA1.csv").readLines().filter { it.isNotBlank() }.map { line ->
                val (onset, hz, duration) = line.split(",").map { it.trim().toDouble() }
                val startMs = (onset * 1000).toLong()
                NoteEvent((69 + 12 * log2(hz / 440.0)).roundToInt(), startMs, startMs + (duration * 1000).toLong(), 100)
            }

            wav.copyTo(File(out, "${name}_voix.wav"), overwrite = true)
            File(out, "${name}_appli.mid").writeBytes(MidiFileWriter().write(analysis.notes))
            File(out, "${name}_reference_musicien.mid").writeBytes(MidiFileWriter().write(reference))

            val durationS = AudioSystem.getAudioFileFormat(wav).let { it.frameLength / it.format.frameRate }
            summary.append("$name (%.0f s) : ${analysis.notes.size} notes trouvées par l'appli, ${reference.size} notes de référence".format(durationS))
            html.append(pianoRollSvg(name, frames, analysis.notes, reference))
            summary.append(", tonalité détectée : ${analysis.key?.let { "${it.displayName} (confiance ${(it.confidence * 100).roundToInt()} %)" } ?: "-"}\n")
        }
        File(out, "LISEZ-MOI.txt").writeText(summary.toString())
        File(out, "comparaison.html").writeText(page(html.toString()))
        println("EXPORT\n$summary")
    }

    /**
     * Piano roll en SVG, lisible sans connaissances : chaque note de l'appli
     * est verte si elle correspond à une note du musicien (début à ±100 ms,
     * même demi-ton), orange sinon ; les notes du musicien que l'appli a
     * ratées sont en pointillés. La voix mesurée est en fond (points gris).
     */
    private fun pianoRollSvg(
        name: String,
        frames: List<com.example.voicetomidi.notes.RawPitchFrame>,
        app: List<NoteEvent>,
        reference: List<NoteEvent>
    ): String {
        if (app.isEmpty() && reference.isEmpty()) return ""
        // Même règle que le benchmark : on autorise une transposition globale d'un demi-ton.
        val (shift, matches) = (-1..1).map { it to matchNotes(app, reference, it) }.maxBy { it.second.size }
        val shifted = app.map { it.copy(midiNote = it.midiNote + shift) }
        val matchedApp = matches.keys
        val matchedRef = matches.values.toSet()

        val all = shifted + reference
        val low = all.minOf { it.midiNote } - 1
        val high = all.maxOf { it.midiNote } + 1
        val endMs = all.maxOf { it.endTimeMs }
        val pxPerSecond = 90.0
        val rowHeight = 18.0
        val labelWidth = 52.0
        val width = labelWidth + endMs / 1000.0 * pxPerSecond + 10
        val height = (high - low + 1) * rowHeight + 18
        fun x(ms: Long) = labelWidth + ms / 1000.0 * pxPerSecond
        fun y(semitones: Double) = (high - semitones + 0.5) * rowHeight
        val names = arrayOf("Do", "Do#", "Ré", "Mi♭", "Mi", "Fa", "Fa#", "Sol", "La♭", "La", "Si♭", "Si")

        val wrong = app.size - matchedApp.size
        val missed = reference.size - matchedRef.size
        val percent = if (reference.isEmpty()) 0 else (100.0 * matchedRef.size / reference.size).roundToInt()
        val svg = StringBuilder()
        svg.append("<section><h2>$name</h2>")
        svg.append("<p class=\"verdict\">Sur les <b>${reference.size}</b> notes chantées, l'appli en a trouvé <b class=\"ok\">${matchedRef.size} justes ($percent %)</b>, ")
        svg.append("en a raté <b class=\"miss\">$missed</b> et a écrit <b class=\"bad\">$wrong</b> notes fausses ou en trop.</p>")
        if (shift != 0) svg.append("<p class=\"note\">L'appli a tout décalé d'un demi-ton : la personne chantait pile entre deux notes, ce n'est pas une erreur de mélodie. Le dessin est recalé.</p>")
        svg.append("<div class=\"scroll\"><svg width=\"%.0f\" height=\"%.0f\">".format(Locale.ROOT, width, height))
        for (n in low..high) {
            val isBlack = n.mod(12) in setOf(1, 3, 6, 8, 10)
            svg.append("<rect x=\"0\" y=\"%.1f\" width=\"%.0f\" height=\"%.1f\" class=\"%s\"/>".format(Locale.ROOT, y(n + 0.5), width, rowHeight, if (isBlack) "black" else "white"))
            svg.append("<text x=\"4\" y=\"%.1f\" class=\"label\">%s%d</text>".format(Locale.ROOT, y(n.toDouble()) + 4, names[n.mod(12)], n / 12 - 1))
        }
        var second = 0L
        while (second * 1000 <= endMs) {
            svg.append("<line x1=\"%.1f\" x2=\"%.1f\" y1=\"0\" y2=\"%.0f\" class=\"grid\"/>".format(Locale.ROOT, x(second * 1000), x(second * 1000), height - 18))
            svg.append("<text x=\"%.1f\" y=\"%.0f\" class=\"label\">%d s</text>".format(Locale.ROOT, x(second * 1000) + 2, height - 4, second))
            second += 1
        }
        for (f in frames) {
            if (f.confidence < 0.5f || f.frequencyHz <= 0f) continue
            val semitones = 69 + 12 * log2(f.frequencyHz / 440.0)
            if (semitones < low - 0.5 || semitones > high + 0.5) continue
            svg.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"1\" class=\"voice\"/>".format(Locale.ROOT, x(f.timestampMs), y(semitones)))
        }
        for ((i, note) in reference.withIndex()) {
            if (i in matchedRef) continue
            svg.append("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" class=\"missed\"/>".format(Locale.ROOT, x(note.startTimeMs), y(note.midiNote + 0.5) + 2, x(note.endTimeMs) - x(note.startTimeMs), rowHeight - 4))
        }
        for ((i, note) in shifted.withIndex()) {
            svg.append("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" class=\"%s\"/>".format(Locale.ROOT, x(note.startTimeMs), y(note.midiNote + 0.5) + 3, x(note.endTimeMs) - x(note.startTimeMs), rowHeight - 6, if (i in matchedApp) "good" else "wrong"))
        }
        svg.append("</svg></div></section>")
        return svg.toString()
    }

    /** Associe chaque note de l'appli (indice) à une note du musicien (indice), comme le benchmark. */
    private fun matchNotes(app: List<NoteEvent>, reference: List<NoteEvent>, shift: Int): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        for ((r, ref) in reference.withIndex()) {
            val candidate = app.indices
                .filter { it !in result && abs(app[it].startTimeMs - ref.startTimeMs) <= 100 && app[it].midiNote + shift == ref.midiNote }
                .minByOrNull { abs(app[it].startTimeMs - ref.startTimeMs) }
            if (candidate != null) result[candidate] = r
        }
        return result
    }

    private fun page(body: String) = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Comparaison VoiceToMidi</title>
<style>
body { font-family: system-ui, sans-serif; background: #f6f5f3; color: #222; margin: 16px; max-width: 1200px; }
section { margin-bottom: 36px; }
.scroll { overflow-x: auto; border: 1px solid #ccc; background: #fff; }
.white { fill: #ffffff; } .black { fill: #f0f0f3; }
.grid { stroke: #dcdce2; stroke-width: 1; }
.label { font-size: 11px; fill: #666; }
.good { fill: #2e9d4f; }
.wrong { fill: #f08a24; }
.missed { fill: none; stroke: #555; stroke-width: 1.5; stroke-dasharray: 4 3; }
.voice { fill: #888; opacity: 0.45; }
.verdict { font-size: 17px; }
.ok { color: #2e9d4f; } .bad { color: #d9731a; } .miss { color: #555; }
.note { color: #666; font-style: italic; }
.how { background: #fff; border: 1px solid #ddd; padding: 12px 16px; border-radius: 6px; line-height: 1.6; }
.sw { display: inline-block; width: 26px; height: 12px; vertical-align: middle; margin-right: 6px; }
</style></head><body>
<h1>Est-ce que l'appli trouve les bonnes notes ?</h1>
<div class="how">
<p>Chaque graphique est un enregistrement d'une vraie personne qui chante (environ 35 secondes). Il se lit comme le
piano roll de FL Studio : <b>de gauche à droite, le temps</b> ; <b>de bas en haut, les notes</b> du grave à l'aigu
(le nom de chaque note est écrit à gauche).</p>
<p><i class="sw" style="background:#2e9d4f"></i><b>Vert</b> : l'appli a trouvé la bonne note, au bon moment.<br>
<i class="sw" style="background:#f08a24"></i><b>Orange</b> : l'appli a écrit une note fausse, ou une note qui n'existe pas.<br>
<i class="sw" style="border:1.5px dashed #555"></i><b>Pointillés</b> : une note chantée que l'appli a ratée.<br>
<i class="sw" style="background:#aaa;height:3px"></i><b>Points gris</b> : la voix elle-même, telle que le micro l'entend.</p>
<p>La "bonne réponse" a été écrite à la main par un musicien. Attention : un deuxième musicien n'était d'accord
avec lui que pour environ 3 notes sur 4, donc une petite part des oranges/pointillés sont discutables.
Faire défiler chaque graphique vers la droite pour voir la suite.</p>
</div>
$body
<p class="note">Voix : jeu de données vocadito (CC BY 4.0, zenodo.org/records/5578807).</p>
</body></html>"""

    private companion object {
        const val EXCERPT_COUNT = 4
    }
}
