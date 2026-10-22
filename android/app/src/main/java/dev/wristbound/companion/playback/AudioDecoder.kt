package dev.wristbound.companion.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Decoded 16-bit PCM, interleaved. */
class DecodedAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int)

/**
 * Decodes an audio file Hermes produced (OGG/Opus, MP3, WAV, M4A...) to 16-bit PCM with the
 * platform's MediaCodec, stopping after [maxSeconds]. The watch has no codec of its own.
 */
object AudioDecoder {
    fun decode(file: File, maxSeconds: Int = PcmConverter.MAX_SECONDS): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(input, null, null, 0)
                codec.start()
                return drain(extractor, codec, input, maxSeconds)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot decode this audio (${e.javaClass.simpleName})", e)
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat, maxSeconds: Int): DecodedAudio {
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = ShortList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                    } else {
                        val sb = buf.asShortBuffer()
                        while (sb.hasRemaining()) out.add(sb.get())
                    }
                    codec.releaseOutputBuffer(o, false)
                    val enough = out.size >= rate.toLong() * channels * maxSeconds
                    if (enough || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        if (out.size == 0) throw IOException("The audio is empty")
        return DecodedAudio(out.toArray(), rate, channels)
    }

    private class ShortList {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
}
