package com.example.voicetomidi.notes

/** Un accord compte au plus autant de notes (la note chantée comprise) : au-delà, il devient flou. */
const val MAX_CHORD_SIZE = 6

/** La « couleur » d'un accord, choisie par l'utilisateur. */
enum class ChordColor(val label: String) {
    /** Dans la tonalité de la mélodie : majeur ou mineur selon la note (règle de base de l'harmonie). */
    AUTO("Auto"),

    /** Joyeux, lumineux, quelle que soit la tonalité. */
    MAJOR("Majeur"),

    /** Doux, triste, quelle que soit la tonalité. */
    MINOR("Mineur")
}

/** Un accord posé sur une note : son nombre de notes (note chantée comprise) et sa couleur. */
data class Chord(val size: Int, val color: ChordColor = ChordColor.AUTO)

/**
 * Accords empilés en tierces, en demi-tons depuis la base : majeur (Do → Mi,
 * Sol, Si, Ré, Fa) et mineur (Do → Mi♭, Sol, Si♭, Ré, Fa).
 */
private val MAJOR_INTERVALS = listOf(4, 7, 11, 14, 17)
private val MINOR_INTERVALS = listOf(3, 7, 10, 14, 17)

/**
 * Les hauteurs d'un accord de [size] notes bâti sur [root] (la note chantée,
 * qui reste en bas, toujours en premier).
 *
 * En [ChordColor.AUTO], on empile une note sur deux de la gamme [key], comme
 * au piano (Do-Mi-Sol, puis Si, Ré, Fa) : l'accord reste dans la tonalité de
 * la mélodie. Une exception : l'accord « diminué » (sur le Si en Do majeur)
 * sonne tendu pour une oreille non avertie, il est remplacé par un accord
 * mineur. Sans tonalité de 7 notes, l'accord est majeur.
 * En [ChordColor.MAJOR] ou [ChordColor.MINOR], la couleur est imposée.
 */
fun chordPitches(root: Int, size: Int, key: ScaleConstraint?, color: ChordColor = ChordColor.AUTO): List<Int> {
    val count = size.coerceIn(1, MAX_CHORD_SIZE)
    val intervals = when (color) {
        ChordColor.MAJOR -> MAJOR_INTERVALS
        ChordColor.MINOR -> MINOR_INTERVALS
        ChordColor.AUTO -> diatonicIntervals(root, key)
    }
    return (listOf(root) + intervals.take(count - 1).map { root + it }).filter { it in 0..127 }
}

/** Intervalles de l'accord dans la gamme [key] (une note sur deux), sans accord diminué. */
private fun diatonicIntervals(root: Int, key: ScaleConstraint?): List<Int> {
    if (key == null || key.scale.semitoneSteps.size != 7) return MAJOR_INTERVALS
    // Notes de la gamme au-dessus de la base : on en garde une sur deux (2e, 4e, 6e...).
    val scaleNotes = (root + 1..root + 36).filter { key.contains(it) }
    val intervals = (1 until MAX_CHORD_SIZE).map { scaleNotes[2 * it - 1] - root }
    val diminished = intervals[0] == 3 && intervals[1] == 6
    return if (diminished) MINOR_INTERVALS else intervals
}

/**
 * Les notes ajoutées par les accords : pour chaque note [i] de [notes] qui a
 * un accord ([chords][i]), ses autres notes, même début, même fin, même force.
 */
fun chordTones(notes: List<NoteEvent>, chords: Map<Int, Chord>, key: ScaleConstraint?): List<NoteEvent> =
    chords.flatMap { (index, chord) ->
        val note = notes.getOrNull(index) ?: return@flatMap emptyList()
        chordPitches(note.midiNote, chord.size, key, chord.color).drop(1).map { note.copy(midiNote = it) }
    }

/** La mélodie avec ses accords, dans l'ordre du temps (ce qui part dans le fichier MIDI). */
fun withChords(notes: List<NoteEvent>, chords: Map<Int, Chord>, key: ScaleConstraint?): List<NoteEvent> =
    (notes + chordTones(notes, chords, key)).sortedWith(compareBy({ it.startTimeMs }, { it.midiNote }))

/**
 * Les accords après la suppression de la note [removed] : son accord part
 * avec elle, et les notes suivantes reculent d'une place.
 */
fun <T> Map<Int, T>.afterRemoving(removed: Int): Map<Int, T> =
    filterKeys { it != removed }.mapKeys { (index, _) -> if (index > removed) index - 1 else index }
