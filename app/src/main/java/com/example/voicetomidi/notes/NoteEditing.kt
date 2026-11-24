package com.example.voicetomidi.notes

/** Ce que le doigt a attrapé sur une note du piano roll. */
enum class NoteGrab {
    /** Le milieu : la note se déplace (dans le temps et en hauteur). */
    MOVE,

    /** Le bord gauche : le début de la note bouge, la fin reste. */
    START,

    /** Le bord droit : la fin de la note bouge (on l'allonge ou on la raccourcit). */
    END
}

/** Une note ne descend jamais sous cette durée quand on l'étire : sinon elle disparaîtrait sous le doigt. */
const val MIN_EDITED_NOTE_MS = 60L

/**
 * La note [index] de [notes] après un glisser de [deltaMs] dans le temps et
 * [deltaSemitones] en hauteur, selon ce qui a été attrapé ([grab]).
 *
 * Une mélodie chantée est faite d'une note à la fois : la note ne peut pas
 * chevaucher ses voisines (elle s'arrête contre elles), ce qui garde aussi
 * l'ordre des notes intact. Elle reste dans [minMs]..[maxMs] (le cadre visible).
 * Seul [NoteGrab.MOVE] change la hauteur ; les bords ne changent que le temps.
 */
fun editedNote(
    notes: List<NoteEvent>,
    index: Int,
    grab: NoteGrab,
    deltaMs: Long,
    deltaSemitones: Int,
    minMs: Long = 0L,
    maxMs: Long = Long.MAX_VALUE
): NoteEvent {
    val note = notes[index]
    // Bornes : la fin de la voisine de gauche, le début de celle de droite. Si l'analyse
    // a laissé un chevauchement, on ne force pas la note à sauter : la borne s'arrête à elle.
    val lower = maxOf(minMs, minOf(notes.getOrNull(index - 1)?.endTimeMs ?: minMs, note.startTimeMs))
    val upper = minOf(maxMs, maxOf(notes.getOrNull(index + 1)?.startTimeMs ?: maxMs, note.endTimeMs))

    return when (grab) {
        NoteGrab.MOVE -> {
            val duration = note.durationMs
            val start = (note.startTimeMs + deltaMs).coerceIn(lower, maxOf(lower, upper - duration))
            note.copy(
                midiNote = (note.midiNote + deltaSemitones).coerceIn(0, 127),
                startTimeMs = start,
                endTimeMs = start + duration
            )
        }
        NoteGrab.START -> {
            val latest = maxOf(lower, note.endTimeMs - MIN_EDITED_NOTE_MS)
            note.copy(startTimeMs = (note.startTimeMs + deltaMs).coerceIn(lower, latest))
        }
        NoteGrab.END -> {
            val earliest = minOf(upper, note.startTimeMs + MIN_EDITED_NOTE_MS)
            note.copy(endTimeMs = (note.endTimeMs + deltaMs).coerceIn(earliest, upper))
        }
    }
}
