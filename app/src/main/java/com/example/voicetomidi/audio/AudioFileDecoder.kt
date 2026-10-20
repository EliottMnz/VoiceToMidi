package com.example.voicetomidi.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Un son décodé, mono, prêt pour le pipeline. */
class DecodedAudio(val samples: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/**
 * Décode un fichier audio (m4a du Dictaphone de l'iPhone, mp3, wav...) avec les
 * décodeurs du téléphone, puis le convertit au format du micro de l'appli
 * (mono, [targetSampleRate] Hz, 16 bits) : une prise importée traverse ainsi
 * exactement le même pipeline qu'une prise enregistrée.
 */
object AudioFileDecoder {

    /** Au-delà, le fichier est refusé (environ 26 Mo en mémoire à 44,1 kHz). */
    const val MAX_DURATION_MS = 5 * 60 * 1000L

    fun decode(context: Context, uri: Uri, targetSampleRate: Int = AudioCaptureManager.SAMPLE_RATE): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("Ce fichier ne contient pas de son")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION) && inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000 > MAX_DURATION_MS) {
                throw IllegalArgumentException("Fichier trop long (5 minutes maximum)")
            }

            val decoded = decodeTrack(extractor, inputFormat)
            val mono = toMono(decoded.samples, decoded.channels)
            return DecodedAudio(resample(mono, decoded.sampleRate, targetSampleRate), targetSampleRate)
        } finally {
            extractor.release()
        }
    }

    private class RawPcm(val samples: FloatArray, val channels: Int, val sampleRate: Int)

    private fun decodeTrack(extractor: MediaExtractor, inputFormat: MediaFormat): RawPcm {
        val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        val out = FloatArrayBuilder()
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var isFloat = false
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (isFloat) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.hasRemaining()) out.add(floats.get())
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.hasRemaining()) out.add(shorts.get() / 32768f)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return RawPcm(out.toArray(), channels, sampleRate)
    }

    private fun toMono(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            sum / channels
        }
    }

    /**
     * Rééchantillonnage linéaire. Suffisant ici : la voix chantée n'a presque
     * pas d'énergie au-delà de quelques kHz, et seule sa hauteur nous intéresse.
     */
    internal fun resample(input: FloatArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val outSize = (input.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return ShortArray(outSize) { i ->
            val position = i * ratio
            val index = position.toInt().coerceAtMost(input.lastIndex)
            val next = (index + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - index).toFloat()
            val value = input[index] + (input[next] - input[index]) * fraction
            (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
}

/**
 * Rejoue un son importé à la place du micro, en accéléré (la courbe défile
 * à l'écran sans faire attendre toute la durée du fichier). Les timestamps du
 * pipeline viennent du nombre d'échantillons, donc l'accélération ne fausse rien.
 */
class FileAudioSource(
    private val audio: DecodedAudio,
    private val speedMultiplier: Float = 4f
) : AudioSource {

    override val sampleRate: Int get() = audio.sampleRate
    override val playbackSpeed: Float get() = speedMultiplier

    @Volatile
    private var isPlaying = false

    override fun captureAudio(): Flow<ShortArray> = flow {
        isPlaying = true
        val chunkSize = 1024
        var i = 0
        while (i < audio.samples.size && isPlaying) {
            val end = minOf(i + chunkSize, audio.samples.size)
            emit(audio.samples.copyOfRange(i, end))
            i = end
            delay((chunkSize * 1000L / sampleRate / speedMultiplier).toLong().coerceAtLeast(1))
        }
        isPlaying = false
    }

    override fun stop() {
        isPlaying = false
    }
}
