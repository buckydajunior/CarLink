package com.carlink.core.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class MusicSpectrumControllerTest {
    private class Harness {
        var now = 0L
        var clocks = 0
        var starts = 0
        var stops = 0
        val ticks = mutableListOf<() -> Unit>()
        val monitor = MusicSpectrumMonitor(LongSupplier { clocks++; now }) { tick ->
            starts++
            ticks += tick
            AutoCloseable { stops++ }
        }
        fun tick(afterMs: Long = 0) { now += afterMs * 1_000_000; ticks.last().invoke() }
    }

    @Test fun noObserverDoesNoCaptureClockOrWorkerWork() {
        val harness = Harness()
        val tap = harness.monitor.createTap(48_000, 2)
        val data = tone(48_000, 1_000, channels = 2)
        repeat(10) { tap.offer(data, 0, data.size) }
        assertEquals(0, harness.starts)
        assertEquals(0, harness.clocks)
        assertFalse(harness.monitor.spectrum.value.hasAudio)
        assertTrue(harness.monitor.spectrum.value.levelsDbFs.all { it == -60f })
    }

    @Test fun fullScaleAndQuietSinesAreCalibratedInDbfsWithoutStereoCancellation() {
        for (rate in listOf(8_000, 44_100, 48_000, 192_000)) {
            for (amplitude in listOf(32_767, 3_277)) {
                val data = tone(rate, 1_000, amplitude, channels = 2, invertRight = true)
                val levels = MusicSpectrumTransform(rate, 2).analyze(data)
                val expected = 20 * log10(amplitude / 32_768.0) - 10 * log10(2.0)
                assertEquals("$rate Hz / amplitude $amplitude", expected, levels[5].toDouble(), 0.08)
                assertEquals(10, levels.size)
                assertEquals(5, levels.indices.maxBy { levels[it] })
                assertTrue(levels.all { it.isFinite() && it in -60f..0f })
            }
        }
    }

    @Test fun stereoLevelUsesMeanPowerRatherThanSummingOrAveragingWaveforms() {
        val mono = tone(48_000, 1_000, 10_000)
        val stereo = ByteArray(mono.size * 2)
        for (frame in 0 until mono.size / 2) {
            stereo[frame * 4] = mono[frame * 2]
            stereo[frame * 4 + 1] = mono[frame * 2 + 1]
        }
        val monoDb = MusicSpectrumTransform(48_000, 1).analyze(mono)[5]
        val oneSideDb = MusicSpectrumTransform(48_000, 2).analyze(stereo)[5]
        assertEquals(-3.0103, (oneSideDb - monoDb).toDouble(), 0.01)
    }

    @Test fun bandEnergyFollowsRealFrequencyAndUnavailableBandsAndSilenceStayAtFloor() {
        for ((rate, frequency, band) in listOf(
            Triple(8_000, 32, 0), Triple(48_000, 63, 1), Triple(44_100, 8_000, 8), Triple(192_000, 16_000, 9),
        )) {
            val fft = MusicSpectrumTransform(rate, 1)
            val levels = fft.analyze(tone(rate, frequency))
            assertEquals("$frequency Hz at $rate Hz", band, levels.indices.maxBy { levels[it] })
            assertTrue(levels[band] > -20f)
            assertTrue(fft.analyze(ByteArray(MusicSpectrumMonitor.windowFrames(rate) * 2)).all { it == -60f })
            if (rate == 8_000) assertTrue(levels.drop(7).all { it == -60f })
        }
        val nyquistLimited = MusicSpectrumTransform(12_000, 1).analyze(tone(12_000, 5_800))
        assertEquals("A partly intersecting octave must not revive an unsupported center", -60f, nyquistLimited[8], 0f)
    }

    @Test fun partialWindowsOffsetsAndBusySlotUseOwnBoundedCopyWithoutChangingPcm() {
        val harness = Harness()
        harness.monitor.observe().use {
            val tap = harness.monitor.createTap(48_000, 1)
            val original = tone(48_000, 1_000)
            var position = 0
            while (position < original.size) {
                val length = minOf(998, original.size - position)
                val padded = ByteArray(length + 8) { 0x55 }
                original.copyInto(padded, 3, position, position + length)
                val unchanged = padded.copyOf()
                tap.offer(padded, 3, length)
                assertArrayEquals(unchanged, padded)
                padded.fill(0) // A decoder can reuse its buffer as soon as the callback returns.
                position += length
            }
            val extra = tone(48_000, 8_000)
            tap.offer(extra, 0, extra.size) // READY slot must skip, not overwrite or grow.
            harness.tick()
            val snapshot = harness.monitor.spectrum.value
            assertTrue(snapshot.hasAudio)
            assertEquals(5, snapshot.levelsDbFs.indices.maxBy { snapshot.levelsDbFs[it] })
            assertEquals(MusicSpectrumTransform(48_000, 1).analyze(original), snapshot.levelsDbFs)
        }
    }

    @Test fun malformedOrEmptyOffersCannotCreateActivity() {
        val harness = Harness()
        harness.monitor.observe().use {
            val tap = harness.monitor.createTap(48_000, 2)
            val data = ByteArray(32)
            for ((offset, length) in listOf(-1 to 8, 0 to -1, 0 to 0, 0 to 3, 30 to 8, Int.MAX_VALUE to 4)) {
                tap.offer(data, offset, length)
            }
            harness.tick()
            assertFalse(harness.monitor.spectrum.value.hasAudio)
        }
    }

    @Test fun firstPacketShowsActivityThenSilenceAndIdleClearWithinHalfASecond() {
        val harness = Harness()
        harness.monitor.observe().use {
            val tap = harness.monitor.createTap(48_000, 1)
            val partial = ByteArray(100)
            tap.offer(partial, 0, partial.size)
            harness.tick()
            assertTrue(harness.monitor.spectrum.value.hasAudio)
            assertTrue(harness.monitor.spectrum.value.levelsDbFs.all { it == -60f })
            harness.tick(440)
            assertTrue(harness.monitor.spectrum.value.hasAudio)
            harness.tick(20)
            assertFalse(harness.monitor.spectrum.value.hasAudio)
            val fresh = tone(48_000, 1_000)
            tap.offer(fresh, 0, fresh.size)
            harness.tick()
            assertEquals("Partial pre-idle samples must not prefix the new window", 5,
                harness.monitor.spectrum.value.levelsDbFs.indices.maxBy { harness.monitor.spectrum.value.levelsDbFs[it] })
        }
    }

    @Test fun lastObserverStopsWorkerAndLateOldEpochCannotPublishAfterReopen() {
        val harness = Harness()
        val first = harness.monitor.observe()
        val second = harness.monitor.observe()
        assertEquals(1, harness.starts)
        val tap = harness.monitor.createTap(48_000, 1)
        val data = tone(48_000, 1_000)
        tap.offer(data, 0, data.size)
        val oldTick = harness.ticks.single()
        first.close()
        first.close()
        assertEquals(0, harness.stops)
        second.close()
        assertEquals(1, harness.stops)
        assertFalse(harness.monitor.spectrum.value.hasAudio)
        val reopened = harness.monitor.observe()
        assertEquals(2, harness.starts)
        oldTick() // A cancelled executor may have already dispatched this callback.
        assertFalse(harness.monitor.spectrum.value.hasAudio)
        val other = tone(48_000, 8_000)
        tap.offer(other, 0, other.size)
        harness.tick()
        assertEquals(8, harness.monitor.spectrum.value.levelsDbFs.indices.maxBy { harness.monitor.spectrum.value.levelsDbFs[it] })
        oldTick()
        assertEquals(8, harness.monitor.spectrum.value.levelsDbFs.indices.maxBy { harness.monitor.spectrum.value.levelsDbFs[it] })
        reopened.close()
        assertEquals(2, harness.stops)
        tap.offer(data, 0, data.size)
        assertFalse(harness.monitor.spectrum.value.hasAudio)
    }

    @Test fun replacementRendererDiscardsOldWindowAndFormatWithoutMixingSources() {
        val harness = Harness()
        harness.monitor.observe().use {
            val old = harness.monitor.createTap(48_000, 1)
            val replacement = harness.monitor.createTap(8_000, 2)
            val oldData = tone(48_000, 1_000)
            val newData = tone(8_000, 250, channels = 2)
            old.offer(oldData, 0, oldData.size)
            replacement.offer(newData, 0, newData.size) // Old READY slot still owns its data.
            harness.tick()
            assertTrue(harness.monitor.spectrum.value.levelsDbFs.all { it == -60f })
            replacement.offer(newData, 0, newData.size)
            harness.tick(100)
            val expected = MusicSpectrumTransform(8_000, 2).analyze(newData)
            assertEquals(expected, harness.monitor.spectrum.value.levelsDbFs)
            old.offer(oldData, 0, oldData.size)
            harness.tick(100)
            assertEquals("Finishing old renderer cannot replace current music", expected,
                harness.monitor.spectrum.value.levelsDbFs)
            harness.tick(500)
            old.offer(oldData, 0, oldData.size)
            harness.tick()
            assertEquals(5, harness.monitor.spectrum.value.levelsDbFs.indices.maxBy { harness.monitor.spectrum.value.levelsDbFs[it] })
        }
    }

    @Test fun anInFlightOldTickCannotPublishOverAReopenedEpoch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        val ticks = mutableListOf<() -> Unit>()
        val monitor = MusicSpectrumMonitor(LongSupplier {
            if (Thread.currentThread().name == "old-spectrum-epoch" && blocked.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            0L
        }) { tick -> ticks += tick; AutoCloseable { } }
        val original = monitor.observe()
        val tap = monitor.createTap(48_000, 1)
        val bass = tone(48_000, 63)
        tap.offer(bass, 0, bass.size)
        val oldTick = ticks.single()
        val oldWorker = Thread({ oldTick() }, "old-spectrum-epoch")
        oldWorker.start()
        var reopened: AutoCloseable? = null
        try {
            assertTrue("The old tick has passed its initial closed check", entered.await(5, TimeUnit.SECONDS))
            original.close()
            reopened = monitor.observe()
            val treble = tone(48_000, 8_000)
            tap.offer(treble, 0, treble.size)
            ticks.last().invoke()
            val fresh = monitor.spectrum.value
            assertEquals(8, fresh.levelsDbFs.indices.maxBy { fresh.levelsDbFs[it] })
            release.countDown()
            oldWorker.join(5_000)
            assertFalse(oldWorker.isAlive)
            assertSame("Late computation must not replace the new session's data", fresh, monitor.spectrum.value)
        } finally {
            release.countDown()
            oldWorker.join(5_000)
            original.close()
            reopened?.close()
        }
    }

    @Test fun readyWindowsPublishAtMostTenTimesPerSecond() {
        val harness = Harness()
        harness.monitor.observe().use {
            val tap = harness.monitor.createTap(48_000, 1)
            val bass = tone(48_000, 63)
            val treble = tone(48_000, 8_000)
            tap.offer(bass, 0, bass.size)
            harness.tick()
            val first = harness.monitor.spectrum.value
            tap.offer(treble, 0, treble.size)
            harness.tick(99)
            assertSame(first, harness.monitor.spectrum.value)
            harness.tick(1)
            assertEquals(8, harness.monitor.spectrum.value.levelsDbFs.indices.maxBy { harness.monitor.spectrum.value.levelsDbFs[it] })
        }
    }

    @Test fun audioProducerNeverWaitsForThePublicationLifecycleLock() {
        val inPublication = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readsOnWorker = AtomicInteger()
        lateinit var tick: () -> Unit
        val monitor = MusicSpectrumMonitor(LongSupplier {
            if (Thread.currentThread().name == "test-spectrum-worker" && readsOnWorker.incrementAndGet() == 2) {
                inPublication.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            0L
        }) { action -> tick = action; AutoCloseable { } }
        val handle = monitor.observe()
        val tap = monitor.createTap(48_000, 1)
        tap.offer(ByteArray(100), 0, 100)
        val worker = Thread({ tick() }, "test-spectrum-worker")
        worker.start()
        try {
            assertTrue(inPublication.await(5, TimeUnit.SECONDS))
            val offered = CountDownLatch(1)
            val producer = Thread { tap.offer(ByteArray(100), 0, 100); offered.countDown() }
            producer.start()
            assertTrue("Audio must not wait for synchronized publication", offered.await(1, TimeUnit.SECONDS))
            producer.join(1_000)
        } finally {
            release.countDown()
            worker.join(5_000)
            handle.close()
        }
    }

    private fun tone(rate: Int, frequency: Int, amplitude: Int = 10_000, channels: Int = 1, invertRight: Boolean = false): ByteArray {
        val frames = MusicSpectrumMonitor.windowFrames(rate)
        return ByteArray(frames * channels * 2).also { bytes ->
            for (frame in 0 until frames) for (channel in 0 until channels) {
                val sign = if (channel == 1 && invertRight) -1 else 1
                val value = (amplitude * sin(2 * PI * frequency * frame / rate)).roundToInt() * sign
                val offset = (frame * channels + channel) * 2
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value shr 8).toByte()
            }
        }
    }
}
