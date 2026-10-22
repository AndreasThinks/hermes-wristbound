package dev.wristbound.companion.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns decoded audio into what the watch speaker plays: mono, 8 kHz, signed 8-bit PCM (the
 * SDK's `SpeakerPcmFormat_8kHz_8bit`). Pure, so the conversion is unit-tested.
 */
object PcmConverter {
    const val WATCH_RATE = 8000

    /** At most this much audio goes to the watch: about 1 MB, bounded in memory and in transfer. */
    const val MAX_SECONDS = 120

    /** Average interleaved 16-bit frames of [channels] channels into one channel. */
    fun toMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /**
     * Resample [mono] from [inRate] to [outRate]. Downsampling averages the input samples that
     * fall in each output period (a box filter), which keeps speech intelligible and removes most
     * aliasing without a heavier filter; upsampling interpolates linearly.
     */
    fun resample(mono: ShortArray, inRate: Int, outRate: Int = WATCH_RATE): ShortArray {
        require(inRate > 0 && outRate > 0)
        if (inRate == outRate || mono.isEmpty()) return mono
        val outCount = (mono.size.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outCount)
        if (inRate > outRate) {
            for (i in 0 until outCount) {
                val from = (i.toLong() * inRate / outRate).toInt()
                val to = (((i + 1).toLong() * inRate / outRate).toInt()).coerceIn(from + 1, mono.size)
                var sum = 0L
                for (j in from until to) sum += mono[j]
                out[i] = (sum / (to - from)).toInt().toShort()
            }
        } else {
            for (i in 0 until outCount) {
                val pos = i.toDouble() * inRate / outRate
                val j = pos.toInt().coerceAtMost(mono.size - 1)
                val k = (j + 1).coerceAtMost(mono.size - 1)
                val t = pos - j
                out[i] = (mono[j] * (1 - t) + mono[k] * t).roundToInt().toShort()
            }
        }
        return out
    }

    /**
     * 16-bit samples to signed 8-bit, scaled so the loudest peak sits just below full scale: the
     * watch speaker is small, and Hermes' speech is often quiet. Silence stays silence.
     */
    fun toSigned8(samples: ShortArray): ByteArray {
        val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: 0
        if (peak == 0) return ByteArray(samples.size)
        val scale = 120.0 / peak
        return ByteArray(samples.size) { i -> (samples[i] * scale).roundToInt().coerceIn(-128, 127).toByte() }
    }

    /** The whole pipeline: decoded interleaved audio to watch PCM, at most [MAX_SECONDS] long. */
    fun forWatch(interleaved: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val mono = toMono(interleaved, channels)
        val limit = sampleRate.toLong() * MAX_SECONDS
        val bounded = if (mono.size > limit) mono.copyOf(limit.toInt()) else mono
        return toSigned8(resample(bounded, sampleRate))
    }
}
