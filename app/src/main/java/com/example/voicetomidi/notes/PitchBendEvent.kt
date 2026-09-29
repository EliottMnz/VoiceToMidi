package com.example.voicetomidi.notes

import kotlin.math.roundToInt

/** Une valeur de pitch bend MIDI (0..16383, 8192 = pas de déviation) à un instant donné. */
data class PitchBendEvent(val timestampMs: Long, val value: Int)

private const val CENTER = 8192
private const val MAX_DEVIATION = 8192
private const val BEND_RANGE_CENTS = 200.0 // ±2 demi-tons, plage standard General MIDI
private const val MIN_INTERVAL_MS = 20L // évite un événement par frame d'analyse (~11.6ms)

/**
 * Reconstruit la courbe de pitch bend (vibrato, imprécision naturelle) de
 * chaque note à partir des mesures brutes de fréquence, pour la préserver à
 * l'export au lieu de l'aplatir sur le demi-ton le plus proche.
 *
 * [originalNotes] sert à retrouver quelles frames appartiennent à quelle
 * note (leurs timestamps réels) ; [outputNotes] est la liste effectivement
 * écrite dans le fichier (même ordre/nombre, mais peut avoir été quantifiée
 * rythmiquement) — chaque événement est repositionné proportionnellement à
 * sa position relative dans la note, pour rester cohérent même après
 * quantification.
 */
fun buildPitchBendCurve(
    originalNotes: List<NoteEvent>,
    outputNotes: List<NoteEvent>,
    rawFrames: List<RawPitchFrame>
): List<PitchBendEvent> {
    require(originalNotes.size == outputNotes.size) { "originalNotes et outputNotes doivent correspondre 1:1" }

    val events = mutableListOf<PitchBendEvent>()
    for (i in originalNotes.indices) {
        val original = originalNotes[i]
        val output = outputNotes[i]
        val originalSpan = (original.endTimeMs - original.startTimeMs).coerceAtLeast(1)
        val outputSpan = (output.endTimeMs - output.startTimeMs).coerceAtLeast(1)

        var lastEmittedMs: Long? = null
        for (frame in rawFrames) {
            if (frame.timestampMs < original.startTimeMs || frame.timestampMs >= original.endTimeMs) continue
            if (frame.frequencyHz <= 0f || frame.confidence <= 0f) continue
            val previous = lastEmittedMs
            if (previous != null && frame.timestampMs - previous < MIN_INTERVAL_MS) continue

            val fraction = (frame.timestampMs - original.startTimeMs).toDouble() / originalSpan
            val outputTimestamp = output.startTimeMs + (fraction * outputSpan).toLong()
            val cents = centsBetween(frame.frequencyHz, output.midiNote).coerceIn(-BEND_RANGE_CENTS, BEND_RANGE_CENTS)
            val bendValue = (CENTER + (cents / BEND_RANGE_CENTS) * MAX_DEVIATION).roundToInt().coerceIn(0, 16383)

            events += PitchBendEvent(outputTimestamp, bendValue)
            lastEmittedMs = frame.timestampMs
        }
    }
    return events
}
