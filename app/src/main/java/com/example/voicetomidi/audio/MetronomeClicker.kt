package com.example.voicetomidi.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Joue un clic de métronome en boucle à un BPM donné, pour valider à
 * l'oreille un tempo calculé par tap tempo avant de l'utiliser.
 */
class MetronomeClicker {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)

    /** Boucle indéfiniment (jusqu'à annulation de la coroutine appelante) en jouant un clic à [bpm]. */
    suspend fun play(bpm: Int) {
        val intervalMs = (60_000L / bpm).coerceAtLeast(50L)
        while (true) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, CLICK_DURATION_MS)
            delay(intervalMs)
        }
    }

    fun release() {
        toneGenerator.release()
    }

    companion object {
        private const val CLICK_DURATION_MS = 60
    }
}
