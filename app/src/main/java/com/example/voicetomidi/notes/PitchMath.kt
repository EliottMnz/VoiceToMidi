package com.example.voicetomidi.notes

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Conversion chromatique standard, sans restriction de gamme : round(69 + 12*log2(f/440)). */
fun frequencyToMidiNote(frequencyHz: Float): Int =
    (69 + 12 * log2(frequencyHz / 440f)).roundToInt()

/** Fréquence exacte (tempérament égal, La4 = 440Hz) d'une note MIDI. */
fun midiNoteToFrequencyHz(midiNote: Int): Float =
    (440.0 * 2.0.pow((midiNote - 69) / 12.0)).toFloat()

/** Écart en cents (1/100 de demi-ton) entre une fréquence et une note MIDI donnée. */
fun centsBetween(frequencyHz: Float, midiNote: Int): Double =
    1200.0 * log2(frequencyHz / midiNoteToFrequencyHz(midiNote))

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Notation anglo-saxonne standard (MIDI 60 = C4, comme dans GarageBand/FL Studio). */
fun midiNoteName(midiNote: Int): String {
    val octave = midiNote / 12 - 1
    val name = NOTE_NAMES[((midiNote % 12) + 12) % 12]
    return "$name$octave"
}

private val FRENCH_NOTE_NAMES = arrayOf("Do", "Do#", "Ré", "Mi♭", "Mi", "Fa", "Fa#", "Sol", "La♭", "La", "Si♭", "Si")

/** Notation française pour l'affichage (MIDI 60 = Do4), même numérotation d'octave que [midiNoteName]. */
fun frenchNoteName(midiNote: Int): String = "${FRENCH_NOTE_NAMES[midiNote.mod(12)]}${midiNote / 12 - 1}"
