package com.example.voicetomidi.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voicetomidi.pitch.YinPitchDetector
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic matériel, pas un test de non-régression : pour chaque source
 * micro Android, joue un La 440 Hz par le haut-parleur de l'appareil,
 * l'enregistre, et mesure le niveau capté et la hauteur reconnue. Sert à
 * choisir la source qui marche sur un appareil donné (certaines Samsung
 * livrent UNPROCESSED ~35 dB trop bas).
 *
 * Lancement explicite seulement :
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.voicetomidi.audio.MicSourceProbe -Pandroid.testInstrumentationRunnerArguments.probe=true
 * Résultats dans logcat (tag MicSourceProbe).
 */
@RunWith(AndroidJUnit4::class)
class MicSourceProbe {

    private val sampleRate = 44_100

    @SuppressLint("MissingPermission")
    @Test
    fun probeEverySource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max * 2 / 3, 0)
        Log.i(TAG, "unprocessed supporte=" + audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))

        val sources = linkedMapOf(
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT
        )
        for ((name, source) in sources) {
            val silence = record(source, playTone = false)
            val tone = record(source, playTone = true)
            if (silence == null || tone == null) {
                Log.i(TAG, "$name : indisponible")
                continue
            }
            val pitch = pitchOf(tone)
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT, "%s : silence %.1f dBFS | La 440 joue %.1f dBFS | hauteur reconnue %.0f Hz (confiance %.2f)",
                    name, dbfs(silence), dbfs(tone), pitch.first, pitch.second
                )
            )
        }
    }

    /**
     * Retard réel du micro : à chaque lecture, âge (ms) de l'échantillon le plus
     * récent reçu, d'après l'horloge du micro (getTimestamp). Pour plusieurs
     * tailles de lecture et sources. Lancement : mêmes arguments que ci-dessus.
     */
    @SuppressLint("MissingPermission")
    @Test
    fun measureInputLatency() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probe") == "true")
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        Log.i(TAG, "minBufferSize = $minBuffer octets (${minBuffer / 2 * 1000 / sampleRate} ms)")
        for ((name, source) in listOf("VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION, "MIC" to MediaRecorder.AudioSource.MIC)) {
            for (readSamples in listOf(minBuffer, 1024, 256)) {
                val recorder = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
                val buffer = ShortArray(readSamples)
                recorder.startRecording()
                var total = 0L
                val ages = mutableListOf<Double>()
                val stamp = android.media.AudioTimestamp()
                val endAt = System.nanoTime() + 3_000_000_000L
                while (System.nanoTime() < endAt) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    total += n
                    if (recorder.getTimestamp(stamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val newestCapturedAt = stamp.nanoTime + (total - stamp.framePosition) * 1_000_000_000L / sampleRate
                        ages += (System.nanoTime() - newestCapturedAt) / 1e6
                    }
                }
                recorder.stop()
                recorder.release()
                val sorted = ages.drop(10).sorted()
                if (sorted.isEmpty()) {
                    Log.i(TAG, "$name lecture de $readSamples : horloge du micro indisponible")
                } else {
                    Log.i(TAG, String.format(Locale.ROOT, "%s lecture de %d echantillons (%d ms) : age du son recu median %.0f ms (min %.0f, max %.0f)",
                        name, readSamples, readSamples * 1000 / sampleRate, sorted[sorted.size / 2], sorted.first(), sorted.last()))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(source: Int, playTone: Boolean): ShortArray? {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (e: Exception) {
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val player = if (playTone) startTone() else null
        Thread.sleep(300)
        val samples = ShortArray(sampleRate * 2)
        recorder.startRecording()
        // On jette la première demi-seconde (démarrage du micro), puis on garde 2 s.
        val warmup = ShortArray(sampleRate / 2)
        var read = 0
        while (read < warmup.size) read += recorder.read(warmup, read, warmup.size - read).coerceAtLeast(0)
        read = 0
        while (read < samples.size) {
            val n = recorder.read(samples, read, samples.size - read)
            if (n <= 0) break
            read += n
        }
        recorder.stop()
        recorder.release()
        player?.let {
            it.stop()
            it.release()
        }
        return samples
    }

    private fun startTone(): AudioTrack {
        val seconds = 4
        val tone = ShortArray(sampleRate * seconds) { (sin(2 * PI * 440.0 * it / sampleRate) * 0.3 * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(tone.size * 2)
            .build()
        track.write(tone, 0, tone.size)
        thread { track.play() }
        return track
    }

    private fun dbfs(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size) / 32768.0
        return if (rms > 0) 20 * log10(rms) else -120.0
    }

    private fun pitchOf(samples: ShortArray): Pair<Float, Float> {
        val detector = YinPitchDetector(sampleRate)
        val start = samples.size / 2
        val result = detector.detectPitch(FloatArray(2048) { samples[start + it] / 32768f })
        return result.frequencyHz to result.confidence
    }

    private companion object {
        const val TAG = "MicSourceProbe"
    }
}
