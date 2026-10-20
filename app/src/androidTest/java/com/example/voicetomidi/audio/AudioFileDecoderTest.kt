package com.example.voicetomidi.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voicetomidi.pitch.YinPitchDetector
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vérifie sur l'appareil que les fichiers importés (m4a du Dictaphone de
 * l'iPhone, wav) sont décodés au format du pipeline : mono, 44,1 kHz, bonne
 * durée et bonne hauteur. Les fichiers sont fabriqués par le test lui-même.
 */
@RunWith(AndroidJUnit4::class)
class AudioFileDecoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun decodesAnAacM4aFileLikeTheIphoneVoiceMemos() {
        val file = File(context.cacheDir, "test_la440.m4a")
        encodeAac(file, sineInterleaved(440.0, durationS = 2.0, sampleRate = 48_000, channels = 2), 48_000, 2)

        val audio = AudioFileDecoder.decode(context, Uri.fromFile(file))

        assertEquals(44_100, audio.sampleRate)
        assertTrue("durée ${audio.durationMs} ms", abs(audio.durationMs - 2000) < 150)
        assertPitchNear(audio, 440.0)
    }

    @Test
    fun decodesAMonoWavFileAtAnotherSampleRate() {
        val file = File(context.cacheDir, "test_la440.wav")
        writeWav(file, sineInterleaved(440.0, durationS = 1.5, sampleRate = 22_050, channels = 1), 22_050)

        val audio = AudioFileDecoder.decode(context, Uri.fromFile(file))

        assertEquals(44_100, audio.sampleRate)
        assertTrue("durée ${audio.durationMs} ms", abs(audio.durationMs - 1500) < 50)
        assertPitchNear(audio, 440.0)
    }

    private fun assertPitchNear(audio: DecodedAudio, expectedHz: Double) {
        val detector = YinPitchDetector(audio.sampleRate)
        val middle = audio.samples.size / 2
        val window = FloatArray(2048) { audio.samples[middle + it] / 32768f }
        val pitch = detector.detectPitch(window)
        assertTrue("hauteur ${pitch.frequencyHz} Hz", abs(pitch.frequencyHz - expectedHz) / expectedHz < 0.02)
    }

    private fun sineInterleaved(hz: Double, durationS: Double, sampleRate: Int, channels: Int): ShortArray {
        val frames = (durationS * sampleRate).toInt()
        return ShortArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / sampleRate
            (sin(2 * PI * hz * t) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { data.putShort(it) }
            fun int32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun int16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            out.writeBytes("RIFF"); int32(36 + samples.size * 2); out.writeBytes("WAVE")
            out.writeBytes("fmt "); int32(16); int16(1); int16(1)
            int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
            out.writeBytes("data"); int32(samples.size * 2)
            out.write(data.array())
        }
    }

    /** Encode du PCM entrelacé en AAC dans un conteneur MP4 (.m4a), comme le Dictaphone. */
    private fun encodeAac(file: File, pcm: ShortArray, sampleRate: Int, channels: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.clear()
                    val count = minOf(buffer.capacity() / 2, pcm.size - fed)
                    val timeUs = fed.toLong() / channels * 1_000_000 / sampleRate
                    if (count <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (k in 0 until count) buffer.putShort(pcm[fed + k])
                        codec.queueInputBuffer(inIndex, 0, count * 2, timeUs, 0)
                        fed += count
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }
}
