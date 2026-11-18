package com.carlink.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class Pcm16EqualizerTest {
    @Test fun offAndEnabledFlatPreserveEveryPcm16ValueExactly() {
        val original = pcm(65_536) { frame, _ -> frame - 32_768 }
        for (settings in listOf(EqualizerSettings(), EqualizerSettings(true))) {
            val data = original.copyOf()
            processor(settings).process(data, 0, data.size)
            assertArrayEquals(original, data)
        }
    }

    @Test fun eachBandCutsItsOwnFrequencyByTheRequestedGain() {
        for (band in 0 until BAND_COUNT) {
            val settings = EqualizerSettings(true, List(BAND_COUNT) { if (it == band) -6f else 0f })
            val tone = tone(BAND_FREQUENCIES_HZ[band].toDouble())
            val before = rms(tone)
            processor(settings).process(tone, 0, tone.size)
            assertEquals("band $band", -6.0, 20.0 * log10(rms(tone) / before), 0.08)
        }
    }

    @Test fun boostChangesToneBalanceAndReservesHeadroom() {
        val settings = EqualizerSettings(true, listOf(0f, 0f, 6f, 0f, 0f))
        val center = tone(910.0)
        val far = tone(30.0)
        val originalCenter = rms(center)
        val originalFar = rms(far)
        processor(settings).process(center, 0, center.size)
        processor(settings).process(far, 0, far.size)
        val centerGain = rms(center) / originalCenter
        val farGain = rms(far) / originalFar
        assertEquals(6.0, 20.0 * log10(centerGain / farGain), 0.12)
        assertTrue("automatic preamp leaves headroom", centerGain < 1.0)
    }

    @Test fun stereoFiltersDoNotLeakIntoTheOtherChannel() {
        val data = pcm(48_000, 2) { frame, channel ->
            if (channel == 0) (10_000 * sin(2 * PI * 910 * frame / 48_000)).roundToInt() else 0
        }
        processor(EqualizerSettings(true, listOf(0f, 0f, -12f, 0f, 0f)), 2).process(data, 0, data.size)
        for (frame in 0 until 48_000) assertEquals(0, sample(data, frame * 4 + 2))
        assertTrue(abs(sample(data, 40_001 * 4)) > 0)
    }

    @Test fun chunkingAndNonzeroByteOffsetsPreserveTheContinuousFilterHistory() {
        val settings = EqualizerSettings(true, listOf(4f, -3f, 6f, -2f, 1f))
        var random = 7
        val source = pcm(12_007, 2) { _, _ ->
            random = random * 1_664_525 + 1_013_904_223
            (random shr 18).coerceIn(-10_000, 10_000)
        }
        val whole = source.copyOf()
        processor(settings, 2).process(whole, 0, whole.size)
        val chunked = source.copyOf()
        val filter = processor(settings, 2)
        val sizes = intArrayOf(1, 7, 960, 2, 117)
        var position = 0
        var chunk = 0
        while (position < source.size) {
            val length = minOf(sizes[chunk++ % sizes.size] * 4, source.size - position)
            val padded = ByteArray(length + 9) { 0x55 }
            source.copyInto(padded, 3, position, position + length)
            filter.process(padded, 3, length)
            assertEquals(0x55.toByte(), padded[2])
            assertEquals(0x55.toByte(), padded[length + 3])
            padded.copyInto(chunked, position, 3, length + 3)
            position += length
        }
        assertArrayEquals(whole, chunked)
    }

    @Test fun independentRenderersDoNotShareHistory() {
        val settings = EqualizerSettings(true, listOf(-6f, 0f, 0f, 0f, 0f))
        val first = processor(settings)
        val second = processor(settings)
        val impulse = pcm(1_000) { frame, _ -> if (frame == 0) 20_000 else 0 }
        first.process(impulse, 0, impulse.size)
        val silence = ByteArray(2_000)
        second.process(silence, 0, silence.size)
        assertArrayEquals(ByteArray(silence.size), silence)
        val tail = ByteArray(2_000)
        first.process(tail, 0, tail.size)
        assertTrue("first renderer retains its own filter tail", tail.any { it != 0.toByte() })
    }

    @Test fun liveEnableGainAndDisableUseSmoothTransitionsThenReturnToExactBypass() {
        var settings = EqualizerSettings().normalized()
        val filter = Pcm16Equalizer(48_000, 1) { settings }
        val dry = pcm(100) { _, _ -> 10_000 }
        filter.process(dry, 0, dry.size)
        settings = EqualizerSettings(true, listOf(12f, 0f, 0f, 0f, 0f)).normalized()
        val transition = pcm(4_800) { _, _ -> 10_000 }
        filter.process(transition, 0, transition.size)
        assertTrue("enable starts near the dry value", abs(sample(transition, 0) - 10_000) < 100)
        assertTrue("no abrupt sample step while enabling", maxStep(transition) < 200)
        settings = EqualizerSettings(true, listOf(-12f, 0f, 0f, 0f, 0f)).normalized()
        val changed = pcm(4_800) { _, _ -> 10_000 }
        filter.process(changed, 0, changed.size)
        assertTrue("no abrupt sample step while changing gain", maxStep(changed) < 200)
        settings = EqualizerSettings().normalized()
        val disabling = pcm(4_800) { _, _ -> 10_000 }
        filter.process(disabling, 0, disabling.size)
        assertTrue("disable starts near the preceding output", abs(sample(disabling, 0) - sample(changed, changed.size - 2)) < 200)
        val untouched = pcm(500) { frame, _ -> frame * 51 - 10_000 }
        val original = untouched.copyOf()
        filter.process(untouched, 0, untouched.size)
        assertArrayEquals(original, untouched)
    }

    @Test fun malformedBuffersAndBandsAboveNyquistSafelyBypass() {
        val settings = EqualizerSettings(true, listOf(0f, 0f, 0f, 0f, 12f))
        val data = pcm(1_000) { frame, _ -> frame * 21 - 10_000 }
        val original = data.copyOf()
        processor(settings, rate = 16_000).process(data, 0, data.size)
        assertArrayEquals(original, data)
        val stereo = processor(EqualizerSettings(true, List(5) { 12f }), 2)
        for ((offset, length) in listOf(-1 to 4, 0 to -1, 0 to 3, 1_999 to 4, Int.MAX_VALUE to 4)) {
            stereo.process(data, offset, length)
            assertArrayEquals(original, data)
        }
    }

    @Test fun fullScaleBoostedMusicIsFiniteAndDoesNotWrapAtThePeaks() {
        val data = tone(910.0, 32_760)
        processor(EqualizerSettings(true, listOf(0f, 0f, 12f, 0f, 0f))).process(data, 0, data.size)
        var peak = 0
        var correlation = 0.0
        for (frame in 24_000 until 48_000) {
            val value = sample(data, frame * 2)
            peak = maxOf(peak, abs(value))
            correlation += value * sin(2 * PI * 910 * frame / 48_000)
        }
        assertTrue("headroom keeps sustained peaks below clipping", peak in 29_000..32_000)
        assertTrue("boosted positive peaks do not wrap negative", correlation > 100_000_000)
    }

    @Test fun filterRingingOnFullScaleStepsSaturatesInsteadOfWrapping() {
        // A steep full-scale step can exceed PCM range even with a cut-only response.
        val data = pcm(8_000) { frame, _ -> if (frame / 1_000 % 2 == 0) -32_768 else 32_767 }
        processor(EqualizerSettings(true, listOf(0f, 0f, 0f, 0f, -12f))).process(data, 0, data.size)
        var positiveClipped = false
        var negativeClipped = false
        for (frame in 1_000 until 8_000) {
            val value = sample(data, frame * 2)
            if (frame / 1_000 % 2 == 1 && value == 32_767) positiveClipped = true
            if (frame / 1_000 % 2 == 0 && value == -32_768) negativeClipped = true
        }
        assertTrue("positive transients saturate", positiveClipped)
        assertTrue("negative transients saturate", negativeClipped)
    }

    private fun processor(settings: EqualizerSettings, channels: Int = 1, rate: Int = 48_000): Pcm16Equalizer {
        val immutable = settings.normalized()
        return Pcm16Equalizer(rate, channels) { immutable }
    }

    private fun tone(frequency: Double, amplitude: Int = 10_000): ByteArray = pcm(48_000) { frame, _ ->
        (amplitude * sin(2 * PI * frequency * frame / 48_000)).roundToInt()
    }

    private fun pcm(frames: Int, channels: Int = 1, value: (Int, Int) -> Int): ByteArray {
        val bytes = ByteArray(frames * channels * 2)
        for (frame in 0 until frames) for (channel in 0 until channels) {
            val sample = value(frame, channel)
            val offset = (frame * channels + channel) * 2
            bytes[offset] = sample.toByte()
            bytes[offset + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    private fun sample(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)

    private fun rms(data: ByteArray): Double {
        var sum = 0.0
        val start = data.size / 2
        for (offset in start until data.size step 2) sum += sample(data, offset).toDouble().pow2()
        return sqrt(sum / ((data.size - start) / 2))
    }

    private fun maxStep(data: ByteArray): Int {
        var largest = 0
        for (offset in 2 until data.size step 2) largest = maxOf(largest, abs(sample(data, offset) - sample(data, offset - 2)))
        return largest
    }

    private fun Double.pow2() = this * this
}
