package com.example.voicetomidi.notes

import kotlin.math.roundToInt

/**
 * Calcule un BPM à partir d'une série de tapotements (tap tempo). Ignore les
 * tapotements trop espacés ([maxGapMs]) — l'utilisateur a probablement fait
 * une pause plutôt que gardé le même rythme, donc seule la série continue la
 * plus récente est prise en compte.
 */
class TapTempoEstimator(
    private val maxGapMs: Long = 2000L,
    private val minBpm: Int = 30,
    private val maxBpm: Int = 300
) {
    private val taps = mutableListOf<Long>()

    /** Enregistre un tapotement à [timestampMs] ; retourne le BPM estimé, ou null si pas assez de données. */
    fun tap(timestampMs: Long): Int? {
        if (taps.isNotEmpty() && timestampMs - taps.last() > maxGapMs) {
            taps.clear()
        }
        taps.add(timestampMs)
        return currentBpm()
    }

    fun currentBpm(): Int? {
        if (taps.size < 2) return null
        val avgMs = taps.zipWithNext { a, b -> b - a }.average()
        if (avgMs <= 0.0) return null
        return (60_000.0 / avgMs).roundToInt().coerceIn(minBpm, maxBpm)
    }

    fun tapCount(): Int = taps.size

    fun reset() {
        taps.clear()
    }
}
