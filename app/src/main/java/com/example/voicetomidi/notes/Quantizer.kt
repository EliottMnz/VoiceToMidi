package com.example.voicetomidi.notes

/**
 * Cale les notes sur une grille temporelle régulière de pas [gridMs].
 * Transformation purement temporelle : ne change jamais les hauteurs ni les
 * vélocités.
 *
 * Un arrondi indépendant du début et de la fin de chaque note peut créer de
 * légers chevauchements ou notes de durée nulle ; une passe de nettoyage
 * garantit ensuite une mélodie monophonique valide.
 */
fun quantize(notes: List<NoteEvent>, gridMs: Double): List<NoteEvent> {
    if (notes.isEmpty()) return notes

    fun snap(timeMs: Long): Long = (Math.round(timeMs / gridMs) * gridMs).toLong()

    val snapped = notes.map { note ->
        val start = snap(note.startTimeMs)
        val minEnd = start + gridMs.toLong().coerceAtLeast(1)
        val end = snap(note.endTimeMs).coerceAtLeast(minEnd)
        note.copy(startTimeMs = start, endTimeMs = end)
    }.toMutableList()

    // L'arrondi indépendant de chaque frontière peut faire chevaucher deux notes
    // voisines : on retaille la précédente pour garder une mélodie monophonique.
    for (i in 0 until snapped.size - 1) {
        val current = snapped[i]
        val next = snapped[i + 1]
        if (current.endTimeMs > next.startTimeMs) {
            val clippedEnd = maxOf(next.startTimeMs, current.startTimeMs + 1)
            snapped[i] = current.copy(endTimeMs = clippedEnd)
        }
    }

    return snapped
}

/** Grille dérivée d'un tempo fixe (BPM) — utile quand le tempo est connu à l'avance. */
fun quantize(notes: List<NoteEvent>, bpm: Int, divisionsPerBeat: Int = 4): List<NoteEvent> =
    quantize(notes, gridMs = 60_000.0 / bpm / divisionsPerBeat)

/**
 * Quantifie sur une grille dérivée de ce qui a été réellement chanté (médiane
 * des écarts entre débuts de notes successives), plutôt qu'un tempo fixe
 * arbitraire sans rapport avec la performance.
 */
fun quantizeAuto(notes: List<NoteEvent>): List<NoteEvent> = quantize(notes, gridMs = estimateGridMs(notes))

/**
 * Estime l'espacement rythmique typique entre les notes (médiane des
 * intervalles entre débuts de notes consécutifs), utilisé comme pas de
 * grille par [quantizeAuto].
 *
 * Limite assumée : ceci n'est PAS une vraie détection de tempo musical (BPM,
 * position des temps forts) — juste l'espacement typique observé entre les
 * notes. Une détection de pulsation fiable sur un chant a cappella sans
 * métronome est un problème intrinsèquement ambigu (plusieurs tempos
 * "plausibles" — le double, la moitié... — expliquent souvent aussi bien les
 * mêmes données), donc cette estimation reste une approximation pragmatique,
 * pas une vraie analyse rythmique.
 */
fun estimateGridMs(notes: List<NoteEvent>, fallbackMs: Double = 125.0): Double {
    if (notes.size < 2) return fallbackMs

    val onsets = notes.map { it.startTimeMs }.sorted()
    val intervals = onsets.zipWithNext { a, b -> b - a }.filter { it > 0 }
    if (intervals.isEmpty()) return fallbackMs

    val sorted = intervals.sorted()
    return sorted[sorted.size / 2].toDouble()
}
