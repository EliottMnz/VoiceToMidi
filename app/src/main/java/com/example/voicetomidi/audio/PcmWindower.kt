package com.example.voicetomidi.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Regroupe un flux de chunks PCM de taille variable en fenêtres d'analyse
 * de taille fixe [windowSize], avec un recouvrement (fenêtre suivante décalée
 * de [hopSize] échantillons). Les échantillons sont normalisés en [-1, 1].
 */
fun Flow<ShortArray>.toAnalysisWindows(
    windowSize: Int,
    hopSize: Int
): Flow<FloatArray> = flow {
    require(hopSize in 1..windowSize) { "hopSize doit être compris entre 1 et windowSize" }

    val buffer = ArrayDeque<Short>(windowSize * 2)
    collect { chunk ->
        for (sample in chunk) {
            buffer.addLast(sample)
        }
        while (buffer.size >= windowSize) {
            val window = FloatArray(windowSize) { i -> buffer[i] / 32768f }
            emit(window)
            repeat(hopSize) { buffer.removeFirst() }
        }
    }
}
