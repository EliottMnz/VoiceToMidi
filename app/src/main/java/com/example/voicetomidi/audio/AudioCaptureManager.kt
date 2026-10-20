package com.example.voicetomidi.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
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
        val audioRecord = openRecorder(bufferSize)
        check(audioRecord != null) {
            "AudioRecord n'a pas pu être initialisé"
        }

        isRecording = true
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Le micro n'a pas pu démarrer (déjà utilisé par une autre application ?)"
            }

            // Petites lectures (~23 ms) : lire tout le tampon d'un coup (~80 ms sur une
            // Galaxy Tab A8) retardait d'autant l'affichage de la voix.
            val buffer = ShortArray(minOf(bufferSize / 2, READ_CHUNK_SAMPLES))

            // Ignore le tout début de la capture : le démarrage du matériel audio
            // produit souvent un bref artefact/clic (observé en pratique sur des
            // exports réels), avant que le signal ne se stabilise.
            var warmupSamplesRemaining = sampleRate * WARMUP_MS / 1000
            warmupFrames = warmupSamplesRemaining.toLong()
            captureTimestamp = null
            while (warmupSamplesRemaining > 0 && isRecording && currentCoroutineContext().isActive) {
                val toRead = minOf(buffer.size, warmupSamplesRemaining)
                val read = audioRecord.read(buffer, 0, toRead)
                if (read > 0) warmupSamplesRemaining -= read
            }

            while (isRecording && currentCoroutineContext().isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val timestamp = AudioTimestamp()
                    if (audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        captureTimestamp = timestamp.framePosition to timestamp.nanoTime
                    }
                    emit(buffer.copyOf(read))
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    // Dernier couple (n° d'échantillon depuis le démarrage, instant de capture en ns)
    // donné par le micro : relie l'horloge du téléphone à la timeline du son, latence
    // matérielle comprise (~200 ms non visibles autrement sur une Galaxy Tab A8).
    @Volatile
    private var captureTimestamp: Pair<Long, Long>? = null
    private var warmupFrames = 0L

    override fun audioPositionMsAt(nanoTime: Long): Long? {
        val (framePosition, capturedAt) = captureTimestamp ?: return null
        val frames = framePosition + (nanoTime - capturedAt) * sampleRate / 1_000_000_000L - warmupFrames
        return frames * 1000 / sampleRate
    }

    /**
     * Ouvre le micro avec VOICE_RECOGNITION, sinon MIC.
     *
     * VOICE_RECOGNITION est réglée pour la reconnaissance vocale : gain calibré,
     * sans contrôle automatique de gain ni débruitage (MIC remonte le bruit de
     * fond et écrase les creux de volume entre notes répétées).
     *
     * Surtout PAS UNPROCESSED : mesuré sur une Samsung Galaxy Tab A8 (SM-X200,
     * Android 13), l'appareil déclare ne pas la prendre en charge mais l'ouvre
     * quand même et livre un son ~35 dB trop faible (un La joué par le
     * haut-parleur ne dépassait le silence que de 2 dB, contre 21 dB avec
     * VOICE_RECOGNITION) : aucune note n'était détectée. Voir MicSourceProbe.
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferSize: Int): AudioRecord? {
        for (source in PREFERRED_SOURCES) {
            val recorder = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
        }
        return null
    }

    /** Demande l'arrêt de la capture en cours ; le flux de [captureAudio] se termine normalement. */
    override fun stop() {
        isRecording = false
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val WARMUP_MS = 100
        private const val READ_CHUNK_SAMPLES = 1024
        private val PREFERRED_SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
    }
}
