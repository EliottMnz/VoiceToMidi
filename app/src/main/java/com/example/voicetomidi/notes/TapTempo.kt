package com.example.voicetomidi.notes

import com.example.voicetomidi.project.MIN_PROJECT_BPM
import com.example.voicetomidi.project.MAX_PROJECT_BPM
import kotlin.math.roundToInt

/** Estime la pulsation sur les six derniers intervalles, avec une horloge monotone en millisecondes. */
class TapTempo {
    private var lastTap: Long? = null
    private val intervals = ArrayDeque<Long>()

    /** Une correction à la molette commence une nouvelle recherche de pulsation. */
    fun reset() {
        lastTap = null
        intervals.clear()
    }

    /** Un tap trop rapproché est ignoré ; une pause ou un retour d'horloge commence une nouvelle série. */
    fun tap(timeMs: Long): Int? {
        val previous = lastTap
        if (previous == null) { lastTap = timeMs; return null }
        val interval = timeMs - previous
        if (interval < 0 || interval > 60_000L / MIN_PROJECT_BPM) {
            intervals.clear()
            lastTap = timeMs
            return null
        }
        if (interval < 60_000L / MAX_PROJECT_BPM) return null
        lastTap = timeMs
        intervals.addLast(interval)
        if (intervals.size > 6) intervals.removeFirst()
        val sorted = intervals.sorted()
        val middle = sorted.size / 2
        // La médiane atténue un tap hésitant ou un temps oublié.
        val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle].toDouble()
        return (60_000.0 / median).roundToInt().coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM)
    }
}
