package com.carlink.core.audio

import com.shilapi.xcertplay.media.HostAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interleaved signed PCM16 little-endian. Owned and called by one renderer's audio thread.
 * Stable off/flat is byte-exact and O(1). The processing loop allocates nothing. Configuration
 * changes crossfade independent filter banks over 20ms rather than changing live IIR poles.
 * Each bank limits actual sample peaks with one gain for both channels, without lookahead or a queue.
 * Ten independent band slots share the same path; only nonzero bands below Nyquist run per sample.
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
            // Limit each bank before blending. A dry target must not inherit the wet bank's
            // remaining gain reduction when a disable/reset transition reaches exact bypass.
            val before = active.renderFrame(data, frame)
            val after = if (blending) target.renderFrame(data, frame) else before
            for (channel in 0 until channels) {
                val position = frame + channel * 2
                val output = if (blending) before[channel] + (after[channel] - before[channel]) * mix else before[channel]
                // Final finite/saturation guard; the linked bank limiter handles over-range peaks.
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

    private class FilterBank(private val sampleRate: Int, private val channels: Int) {
        private val coefficients = DoubleArray(BAND_COUNT * 5)
        private val history = DoubleArray(BAND_COUNT * 2 * channels)
        private val activeBands = IntArray(BAND_COUNT)
        private var activeBandCount = 0
        var bypass = true
            private set
        private val frame = DoubleArray(channels)
        private val release = exp(-1.0 / (sampleRate * 0.100))
        private val holdFrames = max(1, sampleRate / 100)
        private var holdRemaining = 0
        private var limiterGain = 1.0

        fun configure(settings: EqualizerSettings) {
            history.fill(0.0)
            limiterGain = 1.0
            holdRemaining = 0
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
        }

        /** Reused current-frame scratch, not queued audio. Read both channels before writing PCM. */
        fun renderFrame(data: ByteArray, offset: Int): DoubleArray {
            var peak = 0.0
            for (channel in 0 until channels) {
                val position = offset + channel * 2
                val input = ((data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)).toDouble()
                val filtered = sample(input, channel)
                val value = if (filtered.isFinite()) filtered else 0.0
                frame[channel] = value
                peak = max(peak, abs(value))
            }
            if (!bypass) {
                // Preserve unity until a real filtered peak needs attenuation. No theoretical
                // preamp cut or automatic makeup gain; one detector preserves stereo balance.
                val requiredGain = if (peak > PEAK_CEILING) PEAK_CEILING / peak else 1.0
                when {
                    requiredGain < limiterGain -> {
                        limiterGain = requiredGain
                        holdRemaining = holdFrames
                    }
                    holdRemaining > 0 -> holdRemaining--
                    else -> limiterGain = requiredGain + (limiterGain - requiredGain) * release
                }
                for (channel in 0 until channels) frame[channel] *= limiterGain
            }
            return frame
        }

        private fun sample(input: Double, channel: Int): Double {
            if (bypass) return input
            var value = input
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

        private companion object {
            const val PEAK_CEILING = 32_700.0
        }
    }
}
