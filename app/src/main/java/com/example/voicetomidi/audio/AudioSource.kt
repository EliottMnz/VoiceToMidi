package com.example.voicetomidi.audio

import kotlinx.coroutines.flow.Flow

/**
 * Source de PCM 16 bits mono pour le pipeline — soit le micro
 * ([AudioCaptureManager]), soit une mélodie générée ([SyntheticVoiceSource])
 * pour tester sans chanter.
 */
interface AudioSource {
    val sampleRate: Int

    /** Émet les échantillons au fil de l'eau ; se termine normalement après [stop]. */
    fun captureAudio(): Flow<ShortArray>

    fun stop()
}
