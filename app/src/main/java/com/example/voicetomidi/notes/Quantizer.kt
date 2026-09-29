package com.example.voicetomidi.notes

/**
 * Cale les notes sur une grille rythmique régulière (ex: doubles-croches à un
 * tempo donné), pour un rendu "propre" plutôt que les timings réels chantés.
 * Transformation purement temporelle : ne change jamais les hauteurs ni les
 * vélocités.
 *
 * [divisionsPerBeat] = 4 correspond à une grille de doubles-croches (4 par
 * temps). Un arrondi indépendant du début et de la fin de chaque note peut
 * créer de légers chevauchements ou notes de durée nulle ; une passe de
 * nettoyage garantit ensuite une mélodie monophonique valide.
 */
fun quantize(notes: List<NoteEvent>, bpm: Int, divisionsPerBeat: Int = 4): List<NoteEvent> {
    if (notes.isEmpty()) return notes

    val gridMs = 60_000.0 / bpm / divisionsPerBeat
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
