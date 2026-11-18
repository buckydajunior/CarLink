package com.carlink.core.audio

import com.shilapi.xcertplay.media.HostAudio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interleaved signed PCM16 little-endian. Owned and called by one renderer's audio thread.
 * Stable off/flat is byte-exact and O(1). The processing loop allocates nothing. Configuration
 * changes crossfade independent filter banks over 20ms rather than changing live IIR poles.
 */
internal class Pcm16Equalizer(
    private val sampleRate: Int,
    private val channels: Int,
    private val settings: () -> EqualizerSettings,
) : HostAudio.PcmProcessor {
    private var applied = settings()
    private var targetSettings = applied
    private var active = FilterBank(sampleRate, channels).apply { configure(applied) }
    private var target = FilterBank(sampleRate, channels)
    private val transitionFrames = max(1, sampleRate / 50)
    private var remainingFrames = 0
    private val frameBytes = channels * 2

    override fun process(data: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > data.size - length || length % frameBytes != 0) return
        val requested = settings()
        if (remainingFrames == 0 && requested != applied) {
            targetSettings = requested
            target.configure(requested)
            if (active.bypass && target.bypass) applied = requested
            else remainingFrames = transitionFrames
        }
        if (remainingFrames == 0 && active.bypass) return
        var frame = offset
        val end = offset + length
        while (frame < end) {
            val blending = remainingFrames > 0
            val mix = if (blending) (transitionFrames - remainingFrames + 1).toDouble() / transitionFrames else 0.0
            for (channel in 0 until channels) {
                val position = frame + channel * 2
                val input = ((data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)).toDouble()
                val before = active.sample(input, channel)
                val output = if (blending) before + (target.sample(input, channel) - before) * mix else before
                // Reserve headroom for boosts, then saturate exceptional transients; never wrap PCM.
                val value = if (output.isFinite()) output.coerceIn(-32768.0, 32767.0).roundToInt() else 0
                data[position] = value.toByte()
                data[position + 1] = (value shr 8).toByte()
            }
            frame += frameBytes
            if (blending && --remainingFrames == 0) {
                val previous = active
                active = target
                target = previous
                applied = targetSettings
                if (active.bypass) return // Preserve the remaining dry bytes exactly.
            }
        }
    }

    private class FilterBank(private val sampleRate: Int, channels: Int) {
        private val coefficients = DoubleArray(BAND_COUNT * 5)
        private val history = DoubleArray(BAND_COUNT * 2 * channels)
        private val activeBands = IntArray(BAND_COUNT)
        private var activeBandCount = 0
        var bypass = true
            private set
        private var headroom = 1.0

        fun configure(settings: EqualizerSettings) {
            history.fill(0.0)
            bypass = true
            activeBandCount = 0
            for (band in 0 until BAND_COUNT) {
                val base = band * 5
                coefficients[base] = 1.0
                for (index in 1..4) coefficients[base + index] = 0.0
                val frequency = BAND_FREQUENCIES_HZ[band]
                val gain = settings.bandGainsDb.getOrNull(band)?.toDouble() ?: 0.0
                if (!settings.enabled || gain == 0.0 || frequency >= sampleRate / 2.0) continue
                bypass = false
                activeBands[activeBandCount++] = band
                // RBJ peaking EQ, Q = 1: https://www.w3.org/TR/audio-eq-cookbook/
                val amplitude = 10.0.pow(gain / 40.0)
                val omega = 2.0 * PI * frequency / sampleRate
                val alpha = sin(omega) / 2.0
                val cosine = cos(omega)
                val a0 = 1.0 + alpha / amplitude
                coefficients[base] = (1.0 + alpha * amplitude) / a0
                coefficients[base + 1] = -2.0 * cosine / a0
                coefficients[base + 2] = (1.0 - alpha * amplitude) / a0
                coefficients[base + 3] = -2.0 * cosine / a0
                coefficients[base + 4] = (1.0 - alpha / amplitude) / a0
            }
            headroom = 1.0
            if (!bypass) {
                // Check the cascade, including band centers. Unlike summing all positive gains,
                // this avoids excessive attenuation when several distant bands are boosted.
                var peak = 1.0
                val highest = sampleRate * 0.499
                val ratio = highest / 10.0
                for (probe in 0..128) peak = max(peak, magnitude(10.0 * ratio.pow(probe / 128.0)))
                for (band in 0 until BAND_COUNT) {
                    val frequency = BAND_FREQUENCIES_HZ[band].toDouble()
                    if (frequency < highest) peak = max(peak, magnitude(frequency))
                }
                if (peak > 1.000001) headroom = 10.0.pow(-0.5 / 20.0) / peak
            }
        }

        fun sample(input: Double, channel: Int): Double {
            if (bypass) return input
            var value = input * headroom
            for (index in 0 until activeBandCount) {
                val band = activeBands[index]
                val base = band * 5
                val state = channel * BAND_COUNT * 2 + band * 2
                val output = coefficients[base] * value + history[state]
                history[state] = coefficients[base + 1] * value - coefficients[base + 3] * output + history[state + 1]
                history[state + 1] = coefficients[base + 2] * value - coefficients[base + 4] * output
                value = output
            }
            return value
        }

        private fun magnitude(frequency: Double): Double {
            val omega = 2.0 * PI * frequency / sampleRate
            val c1 = cos(omega)
            val s1 = sin(omega)
            val c2 = cos(omega * 2.0)
            val s2 = sin(omega * 2.0)
            var squared = 1.0
            for (index in 0 until activeBandCount) {
                val band = activeBands[index]
                val base = band * 5
                val realB = coefficients[base] + coefficients[base + 1] * c1 + coefficients[base + 2] * c2
                val imagB = coefficients[base + 1] * s1 + coefficients[base + 2] * s2
                val realA = 1.0 + coefficients[base + 3] * c1 + coefficients[base + 4] * c2
                val imagA = coefficients[base + 3] * s1 + coefficients[base + 4] * s2
                squared *= (realB * realB + imagB * imagB) / (realA * realA + imagA * imagA)
            }
            return sqrt(squared)
        }
    }
}
