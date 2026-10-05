package com.example.voicetomidi.notes

import kotlin.math.abs

/** Gamme musicale : degrés en demi-tons depuis la tonique, répétés chaque octave. */
enum class Scale(val displayName: String, val semitoneSteps: List<Int>) {
    CHROMATIC("Chromatique (toutes les notes)", (0..11).toList()),
    MAJOR("Majeure", listOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Mineure naturelle", listOf(0, 2, 3, 5, 7, 8, 10)),
    MAJOR_PENTATONIC("Pentatonique majeure", listOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Pentatonique mineure", listOf(0, 3, 5, 7, 10)),
    BLUES("Blues", listOf(0, 3, 5, 6, 7, 10))
}

private val PITCH_CLASS_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun pitchClassName(pitchClass: Int): String = PITCH_CLASS_NAMES[pitchClass.mod(12)]

/**
 * Une gamme concrète : [scale] appliquée à partir de [rootPitchClass] (0=C,
 * 1=C#, ... 11=B). Restreindre la détection à une gamme réduit le nombre de
 * notes candidates par octave (7, 5...au lieu de 12), ce qui écarte
 * mécaniquement les notes voisines ambiguës — la même logique que la
 * fonction "Key" des plugins voix->MIDI du marché.
 */
data class ScaleConstraint(val scale: Scale, val rootPitchClass: Int) {
    val displayName: String get() = "${PITCH_CLASS_NAMES[rootPitchClass.mod(12)]} ${scale.displayName}"

    private val allowedPitchClasses: Set<Int> =
        scale.semitoneSteps.map { (rootPitchClass + it).mod(12) }.toSet()

    fun contains(midiNote: Int): Boolean = midiNote.mod(12) in allowedPitchClasses

    /** Note MIDI de la gamme la plus proche de [midiNote] (égalité de distance -> la plus grave). */
    fun nearestInScale(midiNote: Int): Int {
        if (contains(midiNote)) return midiNote
        var best = midiNote
        var bestDistance = Int.MAX_VALUE
        for (candidate in (midiNote - 6)..(midiNote + 6)) {
            if (!contains(candidate)) continue
            val distance = abs(candidate - midiNote)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }
}

/** Gamme chromatique à partir de Do : équivaut à "pas de contrainte" (toutes les notes passent). */
val NoScaleConstraint = ScaleConstraint(Scale.CHROMATIC, rootPitchClass = 0)
