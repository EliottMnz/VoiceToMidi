package com.example.voicetomidi.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Métronome joué pendant une prise : [countIn] clics de décompte, puis des
 * clics continus si [keepClicking], sinon du silence.
 *
 * Le clic est un bref bruit (~15 ms) et non un bip : un bip a une hauteur,
 * que le micro capterait et que YIN prendrait pour une note à chaque temps.
 *
 * Le son est écrit d'avance dans un flux continu, donc le temps n°k sort au
 * k-ième "framesPerBeat" : avec l'horloge du haut-parleur
 * ([AudioTrack.getTimestamp], latence de sortie comprise), on sait à quel
 * instant chaque clic a été *entendu* — c'est ce qui permet de placer les
 * temps dans l'enregistrement pour la quantification.
 */
class Metronome(private val sampleRate: Int = 44100) {

    @Volatile
    private var outputTimestamp: Pair<Long, Long>? = null
    @Volatile
    private var playStartNanos = 0L
    private var framesPerBeat = 0
    private var latencyGuessNanos = 0L

    @Volatile
    private var running = false

    /** Joue jusqu'à [stop] (bloquant : à appeler hors du fil de l'interface). */
    suspend fun play(bpm: Int, countIn: Int, keepClicking: Boolean) {
        framesPerBeat = (sampleRate * 60.0 / bpm).roundToInt()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        latencyGuessNanos = minBuffer.toLong() / 2 * 1_000_000_000L / sampleRate
        val accent = beat(CLICK_GAIN)
        val click = beat(CLICK_GAIN * 0.6f)
        val silence = ShortArray(framesPerBeat)
        outputTimestamp = null
        running = true
        try {
            playStartNanos = System.nanoTime()
            track.play()
            var k = 0
            while (running && currentCoroutineContext().isActive) {
                val buffer = when {
                    k < countIn -> if (k == 0) accent else click
                    keepClicking -> if ((k - countIn) % 4 == 0) accent else click
                    else -> silence
                }
                track.write(buffer, 0, buffer.size)
                val ts = AudioTimestamp()
                if (track.getTimestamp(ts)) outputTimestamp = ts.framePosition to ts.nanoTime
                k++
            }
        } finally {
            running = false
            track.pause()
            track.flush()
            track.release()
        }
    }

    fun stop() {
        running = false
    }

    /**
     * Instant ([System.nanoTime]) où le temps n°[beat] (0 = premier clic) sort
     * du haut-parleur. Sans horloge de sortie, estimation par le démarrage et
     * la taille du tampon.
     */
    fun beatNanoTime(beat: Int): Long {
        val beatFrames = beat.toLong() * framesPerBeat
        outputTimestamp?.let { (frame, nanos) ->
            return nanos + (beatFrames - frame) * 1_000_000_000L / sampleRate
        }
        return playStartNanos + latencyGuessNanos + beatFrames * 1_000_000_000L / sampleRate
    }

    /** Temps en train d'être entendu (0 = premier clic), ou -1 avant le premier clic. */
    fun beatAt(nanoTime: Long): Int {
        if (framesPerBeat == 0) return -1
        val first = beatNanoTime(0)
        if (nanoTime < first) return -1
        return ((nanoTime - first) * sampleRate / 1_000_000_000L / framesPerBeat).toInt()
    }

    /** Un temps : clic de bruit à décroissance rapide, puis silence jusqu'au temps suivant. */
    private fun beat(gain: Float): ShortArray {
        val random = Random(42)
        val clickFrames = sampleRate * CLICK_MS / 1000
        return ShortArray(framesPerBeat) { i ->
            if (i >= clickFrames) 0 else {
                val envelope = exp(-i * 6.0 / clickFrames)
                (random.nextFloat() * 2 - 1).times(gain * envelope.toFloat() * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }

    private companion object {
        const val CLICK_MS = 15
        const val CLICK_GAIN = 0.8f
    }
}
