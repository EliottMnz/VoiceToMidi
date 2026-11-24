package com.example.voicetomidi.notes

import kotlin.math.roundToInt

private const val MIN_VELOCITY = 40
private const val MAX_VELOCITY = 127

/** Mappe une amplitude RMS crête vers une vélocité MIDI (1..127), bornée par [minAmplitude]/[maxAmplitude]. */
fun amplitudeToVelocity(
    rms: Float,
    minAmplitude: Float = 0.01f,
    maxAmplitude: Float = 0.5f
): Int {
    val clamped = rms.coerceIn(minAmplitude, maxAmplitude)
    val normalized = (clamped - minAmplitude) / (maxAmplitude - minAmplitude)
    return (MIN_VELOCITY + normalized * (MAX_VELOCITY - MIN_VELOCITY)).roundToInt()
}

/**
 * Force de toutes les notes d'une prise, en % : la même pour chaque note, qu'on
 * ait chanté fort ou doucement. Le volume du micro dépend surtout de la distance
 * à la tablette et du bruit de la pièce, pas de l'intention musicale ; la force
 * se règle ensuite note par note, à la main.
 */
const val DEFAULT_VELOCITY_PERCENT = 80

/** Force en % (0..100) → vélocité MIDI (0..127). */
fun velocityFromPercent(percent: Int): Int = (percent.coerceIn(0, 100) * MAX_VELOCITY / 100.0).roundToInt()

/** Vélocité MIDI (0..127) → force en % (0..100), pour l'affichage. */
fun velocityToPercent(velocity: Int): Int = (velocity.coerceIn(0, MAX_VELOCITY) * 100.0 / MAX_VELOCITY).roundToInt()

/** Les mêmes notes, toutes à la force par défaut ([DEFAULT_VELOCITY_PERCENT]). */
fun List<NoteEvent>.withUniformVelocity(): List<NoteEvent> {
    val velocity = velocityFromPercent(DEFAULT_VELOCITY_PERCENT)
    return map { it.copy(velocity = velocity) }
}
