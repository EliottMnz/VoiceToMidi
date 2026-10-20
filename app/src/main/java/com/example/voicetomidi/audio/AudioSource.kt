package com.example.voicetomidi.audio

import kotlinx.coroutines.flow.Flow

/**
 * Source de PCM 16 bits mono pour le pipeline — soit le micro
 * ([AudioCaptureManager]), soit une mélodie générée ([SyntheticVoiceSource])
 * pour tester sans chanter.
 */
interface AudioSource {
    val sampleRate: Int

    /**
     * Vitesse de défilement du son par rapport au temps réel : 1 pour le micro,
     * plus pour une démo ou un fichier rejoués en accéléré. Sert à dater les
     * appuis du bouton dans la timeline du son.
     */
    val playbackSpeed: Float get() = 1f

    /**
     * Position (ms, timeline du pipeline : 0 = premier échantillon émis) du son
     * capté à l'instant [nanoTime] (horloge [System.nanoTime]), si la source sait
     * le dire précisément ; null sinon (l'appelant estime alors lui-même).
     */
    fun audioPositionMsAt(nanoTime: Long): Long? = null

    /** Émet les échantillons au fil de l'eau ; se termine normalement après [stop]. */
    fun captureAudio(): Flow<ShortArray>

    fun stop()
}
