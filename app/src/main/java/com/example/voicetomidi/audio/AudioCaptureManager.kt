package com.example.voicetomidi.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Capture le micro en PCM 16 bits mono et émet les échantillons bruts au fil de l'eau.
 * L'appelant est responsable d'avoir obtenu la permission RECORD_AUDIO au préalable.
 *
 * [stop] termine le flux normalement (pas par annulation de coroutine), ce qui permet
 * à un consommateur en aval de finir proprement son propre traitement (ex: clore la
 * dernière note en cours) avant que le flux ne se termine.
 */
class AudioCaptureManager(
    override val sampleRate: Int = SAMPLE_RATE
) : AudioSource {

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission")
    override fun captureAudio(): Flow<ShortArray> = flow {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) {
            "Impossible de configurer AudioRecord pour ce périphérique (code $minBufferSize)"
        }

        val bufferSize = minBufferSize * 2
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord n'a pas pu être initialisé"
        }

        isRecording = true
        try {
            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording && currentCoroutineContext().isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(buffer.copyOf(read))
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    /** Demande l'arrêt de la capture en cours ; le flux de [captureAudio] se termine normalement. */
    override fun stop() {
        isRecording = false
    }

    companion object {
        const val SAMPLE_RATE = 44100
    }
}
