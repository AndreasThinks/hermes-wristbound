package dev.wristbound.companion.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmConverterTest {
    @Test
    fun stereoIsAveragedToMono() {
        assertArrayEquals(shortArrayOf(150, -50), PcmConverter.toMono(shortArrayOf(100, 200, -100, 0), 2))
        val mono = shortArrayOf(1, 2, 3)
        assertArrayEquals(mono, PcmConverter.toMono(mono, 1))
    }

    @Test
    fun downsamplingKeepsDurationAndShape() {
        // One second of a 440 Hz tone at 48 kHz becomes one second at 8 kHz.
        val tone = ShortArray(48_000) { (sin(2 * PI * 440 * it / 48_000) * 10_000).toInt().toShort() }
        val out = PcmConverter.resample(tone, 48_000)
        assertEquals(8_000, out.size)
        // Same phase at the same instant (quarter period is at 1/1760 s): positive peak region.
        val peak = out.maxOf { it.toInt() }
        assertTrue("peak $peak", peak in 8_000..10_000)
    }

    @Test
    fun upsamplingInterpolates() {
        val out = PcmConverter.resample(shortArrayOf(0, 100), inRate = 4_000)
        assertEquals(4, out.size)
        assertArrayEquals(shortArrayOf(0, 50, 100, 100), out)
    }

    @Test
    fun quietSpeechIsScaledToFullRangeAsSigned8Bit() {
        val out = PcmConverter.toSigned8(shortArrayOf(0, 1000, -1000, 500))
        assertArrayEquals(byteArrayOf(0, 120, -120, 60), out)
        assertArrayEquals(ByteArray(3), PcmConverter.toSigned8(ShortArray(3)))
    }

    @Test
    fun theWholePipelineIsBoundedToTheMaximumLength() {
        val rate = 16_000
        val long = ShortArray(rate * (PcmConverter.MAX_SECONDS + 10)) { if (it % 2 == 0) 1000 else -1000 }
        val pcm = PcmConverter.forWatch(long, rate, 1)
        assertEquals(PcmConverter.WATCH_RATE * PcmConverter.MAX_SECONDS, pcm.size)
        assertTrue(pcm.all { abs(it.toInt()) <= 127 })
    }
}
