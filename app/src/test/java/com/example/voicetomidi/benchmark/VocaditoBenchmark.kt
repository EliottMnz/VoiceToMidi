package com.example.voicetomidi.benchmark

import com.example.voicetomidi.audio.AudioSource
import com.example.voicetomidi.notes.AnalysisSteps
import com.example.voicetomidi.notes.MelodyAnalyzer
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.TapSegment
import com.example.voicetomidi.notes.ViterbiNoteSegmenter
import com.example.voicetomidi.pipeline.VoiceToMidiPipeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Banc d'essai sur de vraies voix : le jeu de données vocadito (40 extraits de
 * chant solo, CC BY 4.0, https://zenodo.org/records/5578807), annoté note par
 * note par deux musiciens (A1 et A2).
 *
 * Ignoré si la variable d'environnement VOCADITO_DIR (dossier contenant
 * Audio/ et Annotations/) n'est pas définie : ce n'est pas un test de non-
 * régression, c'est une mesure à lancer à la main :
 *
 *   VOCADITO_DIR=... ./gradlew testDebugUnitTest --tests '*VocaditoBenchmark*' -i
 *
 * Les extraits de [HOLDOUT_IDS] ne doivent servir à régler aucun paramètre :
 * ils disent si une amélioration mesurée sur les 30 autres est réelle ou un
 * simple surapprentissage.
 */
class VocaditoBenchmark {

    private data class RefNote(val onsetMs: Long, val semitones: Double, val durationMs: Long) {
        val endMs get() = onsetMs + durationMs
    }

    private data class Score(val matched: Int, val estimated: Int, val reference: Int) {
        val precision get() = if (estimated == 0) 0.0 else matched.toDouble() / estimated
        val recall get() = if (reference == 0) 0.0 else matched.toDouble() / reference
        val f get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        operator fun plus(o: Score) = Score(matched + o.matched, estimated + o.estimated, reference + o.reference)
    }

    private class WavSource(private val samples: ShortArray, override val sampleRate: Int) : AudioSource {
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

    /** Un extrait chargé une fois pour toutes (le passage YIN est le plus coûteux). */
    private class Excerpt(val id: Int, val frames: List<RawPitchFrame>, val a1: List<RefNote>, val a2: List<RefNote>)

    private fun loadExcerpts(): List<Excerpt>? {
        val dir = System.getenv("VOCADITO_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return null
        return (1..40).filter { File(dir, "Audio/vocadito_$it.wav").exists() }.map { id ->
            Excerpt(
                id,
                analyzeFrames(File(dir, "Audio/vocadito_$id.wav")),
                readNotes(File(dir, "Annotations/Notes/vocadito_${id}_notesA1.csv")),
                readNotes(File(dir, "Annotations/Notes/vocadito_${id}_notesA2.csv"))
            )
        }
    }

    private fun automatic(e: Excerpt, steps: AnalysisSteps = AnalysisSteps.AS_SUNG) =
        MelodyAnalyzer(steps = steps).analyze(e.frames).notes.map { it.toRef() }

    private fun withButton(e: Excerpt, steps: AnalysisSteps = AnalysisSteps.AS_SUNG) =
        MelodyAnalyzer(steps = steps).analyze(e.frames, simulatedTaps(e.a1, Random(e.id))).notes.map { it.toRef() }

    /** Comme sur la Galaxy Tab A8 testée : appuis 150 à 300 ms *avant* la voix. */
    private fun withEarlyButton(e: Excerpt) =
        MelodyAnalyzer().analyze(e.frames, simulatedEarlyTaps(e.a1, Random(e.id))).notes.map { it.toRef() }

    private fun oldAlgorithm(e: Excerpt) = ViterbiNoteSegmenter().segment(e.frames).map { it.toRef() }

    @Test
    fun benchmark() {
        val excerpts = loadExcerpts()
        assumeTrue("VOCADITO_DIR non défini : benchmark ignoré", excerpts != null)
        excerpts!!

        val results = excerpts.map { e ->
            mapOf(
                "ancien" to oldAlgorithm(e),
                "auto" to automatic(e),
                "bouton" to withButton(e),
                "bouton avance" to withEarlyButton(e),
                "auto+juste" to automatic(e, AnalysisSteps.CORRECTED),
                "bouton+juste" to withButton(e, AnalysisSteps.CORRECTED),
                "humain A2" to e.a2
            ).mapValues { (_, notes) -> notes }
        }

        for ((label, ids) in listOf("DEV (30 extraits)" to excerpts.filter { it.id !in HOLDOUT_IDS }, "TEST (10 extraits mis de côté)" to excerpts.filter { it.id in HOLDOUT_IDS })) {
            println("BENCH ===== $label =====")
            println("BENCH %-10s | regle appli (±100 ms, demi-ton, transposition)  | COnP standard (±50 ms, ±50 cents)".format("version"))
            for (version in listOf("ancien", "auto", "bouton", "bouton avance", "auto+juste", "bouton+juste", "humain A2")) {
                var ours = Score(0, 0, 0)
                var conp = Score(0, 0, 0)
                for (e in ids) {
                    val notes = results[excerpts.indexOf(e)].getValue(version)
                    ours += appScore(notes, e.a1)
                    conp += match(notes, e.a1, shift = 0, onsetToleranceMs = 50, pitchTolerance = 0.5)
                }
                println("BENCH %-12s | P=%.2f R=%.2f F=%.3f                        | P=%.2f R=%.2f F=%.3f".format(Locale.ROOT, version, ours.precision, ours.recall, ours.f, conp.precision, conp.recall, conp.f))
            }
            for (version in listOf("auto", "bouton", "bouton avance")) {
                val (low, high) = bootstrapDifference(ids.map { e -> appScore(results[excerpts.indexOf(e)].getValue(version), e.a1) }, ids.map { e -> appScore(results[excerpts.indexOf(e)].getValue("ancien"), e.a1) })
                println("BENCH gain de '$version' sur 'ancien' : intervalle a 95 %% = [%+.3f ; %+.3f]%s".format(Locale.ROOT, low, high, if (low > 0) "  (gain reel)" else if (high < 0) "  (perte reelle)" else "  (pas significatif)"))
            }
        }
    }

    /** Où partent les notes ratées : une ligne par version × annotateur, en % des notes de référence. */
    @Test
    fun errorBreakdown() {
        val excerpts = loadExcerpts()
        assumeTrue("VOCADITO_DIR non défini : benchmark ignoré", excerpts != null)
        excerpts!!

        val categories = listOf("OK", "debut decale", "demi-ton", "octave", "coupee", "fusionnee", "autre hauteur", "ratee")
        println("ERR version/annot | " + categories.joinToString(" | ") + " | en trop | biais debut (ms)")
        for ((version, run) in listOf<Pair<String, (Excerpt) -> List<RefNote>>>("ancien" to ::oldAlgorithm, "auto" to { automatic(it) }, "bouton" to { withButton(it) })) {
            val outputs = excerpts.map(run)
            for ((annotator, refOf) in listOf<Pair<String, (Excerpt) -> List<RefNote>>>("A1" to { it.a1 }, "A2" to { it.a2 })) {
                val counts = categories.associateWith { 0 }.toMutableMap()
                var extra = 0
                var total = 0
                val onsetErrors = mutableListOf<Long>()
                for ((i, e) in excerpts.withIndex()) {
                    val reference = refOf(e)
                    val breakdown = classify(outputs[i], reference)
                    breakdown.categories.forEach { counts[it] = counts.getValue(it) + 1 }
                    extra += breakdown.extra
                    total += reference.size
                    onsetErrors += breakdown.onsetErrorsMs
                }
                val bias = onsetErrors.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
                println("ERR %-6s %s | ".format(version, annotator) + categories.joinToString(" | ") { "%4.1f%%".format(Locale.ROOT, 100.0 * counts.getValue(it) / total) } + " | %4.1f%% | %+d".format(Locale.ROOT, 100.0 * extra / total, bias))
            }
        }
    }

    /** Apport de chaque étape : on l'enlève (ou l'ajoute) seule et on regarde ce que devient le F (sur les 30 extraits DEV). */
    @Test
    fun ablation() {
        val excerpts = loadExcerpts()?.filter { it.id !in HOLDOUT_IDS }
        assumeTrue("VOCADITO_DIR non défini : benchmark ignoré", excerpts != null)
        excerpts!!

        val default = AnalysisSteps.AS_SUNG
        val variants = linkedMapOf(
            "defaut (tel que chante)" to default,
            "- porte de bruit relative" to default.copy(relativeNoiseGate = false),
            "- 2e passage (gamme)" to default.copy(keyAwareSecondPass = false),
            "- recollage/hesitations" to default.copy(mergeAndHesitations = false),
            "- separation par volume" to default.copy(energySplit = false),
            "+ calibrage de justesse" to default.copy(tuning = true),
            "+ arbitrage gamme (bouton)" to default.copy(keySnapWithTaps = true)
        )
        for ((name, steps) in variants) {
            val auto = excerpts.map { appScore(automatic(it, steps), it.a1) }.reduce(Score::plus)
            val button = excerpts.map { appScore(withButton(it, steps), it.a1) }.reduce(Score::plus)
            println("ABL %-32s auto F=%.3f   bouton F=%.3f".format(Locale.ROOT, name, auto.f, button.f))
        }
    }

    // --- Métriques ---------------------------------------------------------------------------

    /** Règle historique de l'appli : début à ±100 ms, moins d'un demi-ton, meilleure transposition globale (-1, 0, +1). */
    private fun appScore(estimated: List<RefNote>, reference: List<RefNote>): Score =
        (-1..1).map { match(estimated, reference, it, onsetToleranceMs = 100, pitchTolerance = 0.5) }.maxBy { it.matched }

    private fun bestShift(estimated: List<RefNote>, reference: List<RefNote>): Int =
        (-1..1).maxBy { match(estimated, reference, it, 100, 0.5).matched }

    /**
     * Appariement *optimal* (couplage maximum, comme mir_eval) : chaque note
     * trouvée et chaque note de référence servent au plus une fois, et on
     * maximise le nombre de paires compatibles.
     */
    private fun match(estimated: List<RefNote>, reference: List<RefNote>, shift: Int, onsetToleranceMs: Long, pitchTolerance: Double): Score =
        Score(matching(estimated, reference, shift, onsetToleranceMs, pitchTolerance).size, estimated.size, reference.size)

    /** Couplage maximum (algorithme de Kuhn) ; renvoie référence -> indice de la note trouvée. */
    private fun matching(estimated: List<RefNote>, reference: List<RefNote>, shift: Int, onsetToleranceMs: Long, pitchTolerance: Double): Map<Int, Int> {
        val candidates = reference.map { ref ->
            estimated.indices.filter { abs(estimated[it].onsetMs - ref.onsetMs) <= onsetToleranceMs && abs(estimated[it].semitones + shift - ref.semitones) < pitchTolerance }
        }
        val owner = IntArray(estimated.size) { -1 }
        fun augment(r: Int, seen: BooleanArray): Boolean {
            for (e in candidates[r]) {
                if (seen[e]) continue
                seen[e] = true
                if (owner[e] == -1 || augment(owner[e], seen)) {
                    owner[e] = r
                    return true
                }
            }
            return false
        }
        for (r in reference.indices) augment(r, BooleanArray(estimated.size))
        return owner.withIndex().filter { it.value >= 0 }.associate { it.value to it.index }
    }

    private class Breakdown(val categories: List<String>, val extra: Int, val onsetErrorsMs: List<Long>)

    /**
     * Range chaque note de référence dans une catégorie (première qui
     * s'applique), et compte les notes trouvées qui ne recouvrent aucune
     * note de référence ("en trop").
     */
    private fun classify(estimatedRaw: List<RefNote>, reference: List<RefNote>): Breakdown {
        val shift = bestShift(estimatedRaw, reference)
        val estimated = estimatedRaw.map { it.copy(semitones = it.semitones + shift) }
        val ok = matching(estimated, reference, 0, 100, 0.5)
        fun samePitch(a: Double, b: Double) = abs(a - b) < 0.5
        fun overlaps(e: RefNote, r: RefNote) = e.onsetMs < r.endMs && e.endMs > r.onsetMs

        val categories = reference.mapIndexed { r, ref ->
            if (r in ok) return@mapIndexed "OK"
            val overlapping = estimated.filter { overlaps(it, ref) }
            val near = estimated.filter { abs(it.onsetMs - ref.onsetMs) <= 100 }
            val otherRefs = reference.filterIndexed { i, _ -> i != r }
            when {
                estimated.any { samePitch(it.semitones, ref.semitones) && abs(it.onsetMs - ref.onsetMs) in 101..250 } -> "debut decale"
                near.any { abs(abs(it.semitones - ref.semitones).roundToInt() - 1) == 0 } -> "demi-ton"
                (near + overlapping).any { val d = abs(it.semitones - ref.semitones).roundToInt(); d == 12 || d == 24 } -> "octave"
                overlapping.count { samePitch(it.semitones, ref.semitones) } >= 2 -> "coupee"
                overlapping.any { e -> samePitch(e.semitones, ref.semitones) && otherRefs.any { overlaps(e, it) && samePitch(e.semitones, it.semitones) } } -> "fusionnee"
                overlapping.isNotEmpty() -> "autre hauteur"
                else -> "ratee"
            }
        }
        val extra = estimated.count { e -> reference.none { overlaps(e, it) } }
        val onsetErrors = ok.map { (r, e) -> estimated[e].onsetMs - reference[r].onsetMs }
        return Breakdown(categories, extra, onsetErrors)
    }

    /**
     * Intervalle à 95 % du gain de F (somme des extraits) de [a] sur [b], par
     * bootstrap apparié : on retire 1000 fois les extraits avec remise.
     */
    private fun bootstrapDifference(a: List<Score>, b: List<Score>): Pair<Double, Double> {
        val random = Random(42)
        val differences = (1..1000).map {
            val picks = List(a.size) { random.nextInt(a.size) }
            picks.map { a[it] }.reduce(Score::plus).f - picks.map { b[it] }.reduce(Score::plus).f
        }.sorted()
        return differences[25] to differences[974]
    }

    // --- Données -----------------------------------------------------------------------------

    /** Fait passer le fichier par le vrai pipeline de l'appli (fenêtrage + YIN + RMS). */
    private fun analyzeFrames(wav: File): List<RawPitchFrame> = runBlocking {
        val stream = AudioSystem.getAudioInputStream(wav)
        val format = stream.format
        require(format.channels == 1 && format.sampleSizeInBits == 16) { "WAV mono 16 bits attendu : $format" }
        val bytes = stream.readAllBytes()
        val order = if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(order).asShortBuffer().get(samples)

        val pipeline = VoiceToMidiPipeline(audioSource = WavSource(samples, format.sampleRate.toInt()))
        pipeline.run().toList().map { RawPitchFrame(it.timestampMs, it.pitch.frequencyHz, it.pitch.confidence, it.rmsAmplitude) }
    }

    /**
     * Appuis d'un utilisateur imaginaire qui suit la mélodie au doigt : en
     * retard de 40 à 120 ms au début de chaque note, et relâche avec ±60 ms
     * d'erreur.
     */
    private fun simulatedTaps(reference: List<RefNote>, random: Random): List<TapSegment> =
        reference.map { note ->
            val start = note.onsetMs + random.nextLong(40, 121)
            val end = note.onsetMs + note.durationMs + random.nextLong(-60, 61)
            TapSegment(start, maxOf(end, start + 30))
        }

    private fun simulatedEarlyTaps(reference: List<RefNote>, random: Random): List<TapSegment> =
        reference.map { note ->
            val lead = random.nextLong(150, 301)
            TapSegment(note.onsetMs - lead, note.onsetMs + note.durationMs - lead + random.nextLong(-60, 61))
        }

    private fun readNotes(file: File): List<RefNote> =
        file.readLines().filter { it.isNotBlank() }.map { line ->
            val (onset, hz, duration) = line.split(",").map { it.trim().toDouble() }
            RefNote((onset * 1000).toLong(), 69 + 12 * log2(hz / 440.0), (duration * 1000).toLong())
        }

    private fun NoteEvent.toRef() = RefNote(startTimeMs, midiNote.toDouble(), durationMs)

    private companion object {
        /** 1 extrait sur 4, jamais utilisé pour régler un paramètre. */
        val HOLDOUT_IDS = setOf(4, 8, 12, 16, 20, 24, 28, 32, 36, 40)
    }
}
