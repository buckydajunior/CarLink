package com.carlink.core.audio

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recent post-EQ music, ordered by [EqualizerController.frequenciesHz]. Levels are Hann-windowed
 * band RMS in dBFS, averaged as power across channels, clamped to -60..0. A full-scale sine in a
 * band reads approximately -3dBFS. [hasAudio] means recent PCM, including digital silence; it does
 * not claim audibility. No PCM is recorded or exposed. Centers at/above Nyquist stay at the floor.
 */
data class MusicSpectrumSnapshot(
    val levelsDbFs: List<Float> = List(BAND_COUNT) { SPECTRUM_FLOOR_DB_FS },
    val hasAudio: Boolean = false,
)

/** Work exists only while an EQ screen holds an observation handle. Closing is idempotent. */
object MusicSpectrumController {
    private val monitor = MusicSpectrumMonitor()
    val spectrum: StateFlow<MusicSpectrumSnapshot> = monitor.spectrum
    fun observe(): AutoCloseable = monitor.observe()

    internal fun createTap(sampleRate: Int, channels: Int): MusicSpectrumTap = monitor.createTap(sampleRate, channels)
}

internal class MusicSpectrumTap(
    private val monitor: MusicSpectrumMonitor,
    private val renderer: Long,
    private val sampleRate: Int,
    private val channels: Int,
) {
    fun offer(data: ByteArray, offset: Int, length: Int) =
        monitor.offer(renderer, sampleRate, channels, data, offset, length)
}

/** One bounded capture window; producers never wait, allocate, transform or enqueue playback. */
internal class MusicSpectrumMonitor(
    private val nowNanos: LongSupplier = LongSupplier { System.nanoTime() },
    private val schedule: (() -> Unit) -> AutoCloseable = { tick ->
        val executor = Executors.newSingleThreadScheduledExecutor { action ->
            Thread(action, "carlink-music-spectrum").apply { isDaemon = true }
        }
        executor.scheduleAtFixedRate({ tick() }, 0, 10, TimeUnit.MILLISECONDS)
        AutoCloseable { executor.shutdownNow() }
    },
) {
    private val lifecycle = Any()
    private val mutable = MutableStateFlow(IDLE)
    val spectrum = mutable.asStateFlow()
    private val rendererIds = AtomicLong()
    @Volatile private var session: CaptureSession? = null

    fun createTap(sampleRate: Int, channels: Int): MusicSpectrumTap {
        require(sampleRate in 8_000..192_000 && channels in 1..2)
        return MusicSpectrumTap(this, rendererIds.incrementAndGet(), sampleRate, channels)
    }

    fun observe(): AutoCloseable {
        val observed = synchronized(lifecycle) {
            val existing = session
            if (existing != null) {
                existing.observers++
                existing
            } else {
                CaptureSession().also { created ->
                    session = created
                    mutable.value = IDLE
                    try {
                        created.worker = schedule { created.tick() }
                    } catch (failure: Exception) {
                        created.closed = true
                        session = null
                        throw failure
                    }
                }
            }
        }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(lifecycle) {
                if (session === observed && --observed.observers == 0) {
                    observed.closed = true
                    session = null
                    mutable.value = IDLE
                    observed.worker?.close()
                }
            }
        }
    }

    fun offer(renderer: Long, rate: Int, channels: Int, data: ByteArray, offset: Int, length: Int) {
        // No observer: no clock read, buffer, lock, copy or scheduler activity.
        val capture = session ?: return
        val frameBytes = channels * 2
        if (offset < 0 || length <= 0 || offset > data.size - length || length % frameBytes != 0) return
        capture.offer(renderer, rate, channels, data, offset, length)
    }

    private inner class CaptureSession {
        @Volatile var closed = false
        var observers = 1 // lifecycle lock only
        var worker: AutoCloseable? = null
        private val slot = AtomicInteger(FILLABLE)
        private val pcm = ByteArray(MAX_WINDOW_FRAMES * 2 * 2)
        private val selectedRenderer = AtomicLong()
        private val lastPacket = AtomicLong(NO_TIME)
        // Written only by the owner of the slot; published/consumed via the atomic slot state.
        private var capturedFrames = 0
        private var capturedRenderer = 0L
        private var capturedRate = 0
        private var capturedChannels = 0
        private var captureLastPacket = NO_TIME
        private var lastAnalysis = NO_TIME // worker only
        private var transform: MusicSpectrumTransform? = null // worker only

        fun offer(renderer: Long, rate: Int, channels: Int, data: ByteArray, offset: Int, length: Int) {
            if (closed) return
            val now = nowNanos.asLong
            val selected = selectedRenderer.get()
            if (selected != renderer) {
                val last = lastPacket.get()
                // A replacement wins over an old renderer that is still finishing. An older
                // renderer may take over again only after the selected source has gone idle.
                if (selected != 0L && renderer < selected && last != NO_TIME && now - last < IDLE_NANOS) return
                if (!selectedRenderer.compareAndSet(selected, renderer)) return
            }
            lastPacket.set(now)
            if (!slot.compareAndSet(FILLABLE, WRITING)) return
            var ready = false
            try {
                if (closed || selectedRenderer.get() != renderer) return
                if (capturedRenderer != renderer || captureLastPacket == NO_TIME || now - captureLastPacket >= IDLE_NANOS) {
                    capturedFrames = 0
                    capturedRenderer = renderer
                    capturedRate = rate
                    capturedChannels = channels
                }
                val frameBytes = channels * 2
                val frames = minOf(length / frameBytes, windowFrames(rate) - capturedFrames)
                System.arraycopy(data, offset, pcm, capturedFrames * frameBytes, frames * frameBytes)
                capturedFrames += frames
                captureLastPacket = now
                ready = capturedFrames == windowFrames(rate)
            } finally {
                slot.set(if (ready) READY else FILLABLE)
            }
        }

        fun tick() {
            if (closed) return
            val now = nowNanos.asLong
            val last = lastPacket.get()
            if (last == NO_TIME || now - last >= IDLE_NANOS) {
                discardIdleWindow(now)
                publish(IDLE, 0L)
                return
            }
            // Receiving silence is still a live PCM stream. Publish activity before the first
            // whole window is available, without inventing frequency levels.
            if (!mutable.value.hasAudio) publish(MusicSpectrumSnapshot(IDLE.levelsDbFs, true), selectedRenderer.get())
            if (lastAnalysis != NO_TIME && now - lastAnalysis < REFRESH_NANOS) return
            if (!slot.compareAndSet(READY, READING)) return
            try {
                if (capturedRenderer != selectedRenderer.get()) return
                val fft = transform?.takeIf { it.sampleRate == capturedRate && it.channels == capturedChannels }
                    ?: MusicSpectrumTransform(capturedRate, capturedChannels).also { transform = it }
                val levels = fft.analyze(pcm)
                lastAnalysis = now
                publish(MusicSpectrumSnapshot(levels, true), capturedRenderer)
            } finally {
                capturedFrames = 0
                slot.set(FILLABLE)
            }
        }

        private fun discardIdleWindow(now: Long) {
            val previous = slot.get()
            if ((previous == FILLABLE || previous == READY) && slot.compareAndSet(previous, READING)) {
                val last = lastPacket.get()
                if (last == NO_TIME || now - last >= IDLE_NANOS) {
                    capturedFrames = 0
                    capturedRenderer = 0L
                    captureLastPacket = NO_TIME
                    slot.set(FILLABLE)
                } else slot.set(previous)
            }
        }

        private fun publish(value: MusicSpectrumSnapshot, renderer: Long) = synchronized(lifecycle) {
            // Serialize with final handle close/resubscribe. An in-flight FFT from an old epoch
            // cannot replace the new screen's idle/active state, nor an obsolete renderer's data.
            if (session !== this || closed) return@synchronized
            val last = lastPacket.get()
            val recent = last != NO_TIME && nowNanos.asLong - last < IDLE_NANOS
            if (value.hasAudio && (!recent || selectedRenderer.get() != renderer)) return@synchronized
            if (!value.hasAudio && recent) return@synchronized
            mutable.value = value
        }
    }

    internal companion object {
        private const val FILLABLE = 0
        private const val WRITING = 1
        private const val READY = 2
        private const val READING = 3
        private const val NO_TIME = Long.MIN_VALUE
        private const val IDLE_NANOS = 450_000_000L // 10ms worker tick leaves margin within 500ms.
        private const val REFRESH_NANOS = 100_000_000L
        private const val MAX_WINDOW_FRAMES = 16_384
        private val IDLE = MusicSpectrumSnapshot(Collections.unmodifiableList(List(BAND_COUNT) { SPECTRUM_FLOOR_DB_FS }))

        // Power-of-two windows scaled by rate; ~85–93ms at the common 44.1/48kHz rates.
        fun windowFrames(rate: Int) = when {
            rate <= 12_000 -> 1_024
            rate <= 24_000 -> 2_048
            rate <= 48_000 -> 4_096
            rate <= 96_000 -> 8_192
            else -> 16_384
        }
    }
}

/** Worker-only radix-2 FFT. Reuses transform/scratch arrays across windows of the same format. */
internal class MusicSpectrumTransform(val sampleRate: Int, val channels: Int) {
    private val count = MusicSpectrumMonitor.windowFrames(sampleRate)
    private val window = DoubleArray(count) { 0.5 - 0.5 * cos(2 * PI * it / (count - 1)) }
    private val windowPower = window.sumOf { it * it }
    private val real = DoubleArray(count)
    private val imaginary = DoubleArray(count)
    private val powers = DoubleArray(BAND_COUNT)
    private val binBand = IntArray(count / 2 + 1) { bin ->
        val frequency = bin.toDouble() * sampleRate / count
        BAND_FREQUENCIES_HZ.indices.firstOrNull { band ->
            val center = BAND_FREQUENCIES_HZ[band].toDouble()
            val low = if (band == 0) center / sqrt(2.0) else sqrt(center * BAND_FREQUENCIES_HZ[band - 1])
            val high = if (band == BAND_COUNT - 1) center * sqrt(2.0) else sqrt(center * BAND_FREQUENCIES_HZ[band + 1])
            center < sampleRate / 2.0 && frequency >= low && frequency < high
        } ?: -1
    }

    fun analyze(pcm: ByteArray): List<Float> {
        powers.fill(0.0)
        for (channel in 0 until channels) {
            for (frame in 0 until count) {
                val offset = (frame * channels + channel) * 2
                val value = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
                real[frame] = value / 32_768.0 * window[frame]
                imaginary[frame] = 0.0
            }
            fft()
            for (bin in binBand.indices) {
                val band = binBand[bin]
                if (band < 0) continue
                val sides = if (bin == 0 || bin == count / 2) 1.0 else 2.0
                powers[band] += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * sides
            }
        }
        val normalization = count * windowPower * channels
        return Collections.unmodifiableList(List(BAND_COUNT) { band ->
            val power = powers[band] / normalization
            if (power <= 0.0 || !power.isFinite()) SPECTRUM_FLOOR_DB_FS
            else (10 * log10(power)).toFloat().coerceIn(SPECTRUM_FLOOR_DB_FS, 0f)
        })
    }

    private fun fft() {
        var reverse = 0
        for (index in 1 until count) {
            var bit = count shr 1
            while (reverse and bit != 0) {
                reverse = reverse xor bit
                bit = bit shr 1
            }
            reverse = reverse xor bit
            if (index < reverse) {
                val r = real[index]; real[index] = real[reverse]; real[reverse] = r
                val i = imaginary[index]; imaginary[index] = imaginary[reverse]; imaginary[reverse] = i
            }
        }
        var length = 2
        while (length <= count) {
            val stepReal = cos(-2 * PI / length)
            val stepImaginary = sin(-2 * PI / length)
            var start = 0
            while (start < count) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (index in 0 until length / 2) {
                    val even = start + index
                    val odd = even + length / 2
                    val r = real[odd] * wReal - imaginary[odd] * wImaginary
                    val i = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - r
                    imaginary[odd] = imaginary[even] - i
                    real[even] += r
                    imaginary[even] += i
                    val nextReal = wReal * stepReal - wImaginary * stepImaginary
                    wImaginary = wReal * stepImaginary + wImaginary * stepReal
                    wReal = nextReal
                }
                start += length
            }
            length *= 2
        }
    }
}

internal const val SPECTRUM_FLOOR_DB_FS = -60f
