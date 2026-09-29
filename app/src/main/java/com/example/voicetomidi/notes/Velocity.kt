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
