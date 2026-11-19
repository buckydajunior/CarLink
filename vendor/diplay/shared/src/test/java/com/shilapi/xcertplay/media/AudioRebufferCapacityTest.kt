package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [AudioRebufferCapacityTest.CapacityTrack::class])
class AudioRebufferCapacityTest {
    @Test fun residualAudioCountsTowardRestartBeforeAPausedTrackFills() {
        for (millis in MediaAudioBuffer.presets) {
            val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, millis)
            val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)
            CapacityTrack.reset(plan.trackBufferBytes)
            CapacityTrack.queued = threshold / 2
            CapacityTrack.playing = true
            CapacityTrack.audible = true
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(plan.trackBufferBytes).build()
            val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
            val renderer = type.declaredConstructors.single { it.parameterCount == 13 }
                .apply { isAccessible = true }.newInstance(
                    AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"), false, false, 0, 0,
                    AudioFocusCoordinator(null, false, false), 0, millis, { _: String -> }, 0L, false, null, false)
            fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
            fun get(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
            try {
                set("track", track)
                set("mappedChannel", AudioChannel.MEDIA)
                set("playbackStarted", true)
                set("startThresholdBytes", threshold)
                set("lastPcmWriteNs", System.nanoTime())
                (get("bufferProgress") as AudioBufferProgress).written(CapacityTrack.queued)
                type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)
                assertFalse(CapacityTrack.playing)
                type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(renderer, ByteArray(threshold), 0, threshold)
                assertFalse("${millis}ms buffer blocked before play()", CapacityTrack.blocked)
                assertTrue("${millis}ms buffer never resumed", CapacityTrack.playing)
            } finally { track.release() }
        }
    }

    @Test fun callsStartAtTheirFrameDurationAndNeverUseMediaTrimOrRebuffering() {
        val before = HostAudio.callsAsMedia
        try {
            for (rate in listOf(8_000, 16_000, 48_000)) for (asMedia in listOf(false, true)) {
                HostAudio.callsAsMedia = asMedia
                val frameBytes = rate * 2 / 50 // 20ms mono PCM16
                CapacityTrack.reset(frameBytes * 2)
                withCallRenderer(rate) { type, renderer ->
                    assertEquals(if (asMedia) AudioChannel.MEDIA else AudioChannel.PHONE, get(type, renderer, "mappedChannel"))
                    assertEquals(frameBytes, get(type, renderer, "startThresholdBytes"))
                    assertNull("Call clock is not music clock trim", get(type, renderer, "clockTrim"))
                    write(type, renderer, ByteArray(frameBytes))
                    assertTrue("$rate Hz call starts after one frame", CapacityTrack.playing)
                    assertEquals(frameBytes, CapacityTrack.bytesAtPlay)
                    assertFalse(CapacityTrack.blocked)
                    if (Build.VERSION.SDK_INT >= 31) assertTrue("Native threshold accepts one call frame", CapacityTrack.audible)
                    else {
                        assertFalse("play() alone does not bypass the two-window native buffer", CapacityTrack.audible)
                        write(type, renderer, ByteArray(frameBytes))
                        assertTrue("The older-platform effective buffer starts within two frames", CapacityTrack.audible)
                    }
                    // An underrun indication plus empty packet queue must not pause a live call
                    // for a music refill, even when Android routes that call through MEDIA.
                    type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)
                    assertTrue("Call must not enter music rebuffering", CapacityTrack.playing)
                }
            }
        } finally { HostAudio.callsAsMedia = before }
    }

    @Test fun aCallStartsBeforeABlockingWriteCanFillASmallerGrantedTrack() {
        CapacityTrack.reset(capacity = 256)
        withCallRenderer(16_000) { type, renderer ->
            write(type, renderer, ByteArray(640))
            assertFalse("Paused call writes must fit the granted capacity", CapacityTrack.blocked)
            assertTrue(CapacityTrack.playing)
            assertEquals(128, CapacityTrack.bytesAtPlay)
            assertTrue("Oversize writes progress once the native start level is reached", CapacityTrack.audible)
        }
    }

    @Test fun aCallStillHonorsTheDevicesLargerStartupMinimum() {
        CapacityTrack.reset(capacity = 4096, minimum = 1280)
        withCallRenderer(16_000) { type, renderer ->
            write(type, renderer, ByteArray(640))
            assertFalse(CapacityTrack.playing)
            write(type, renderer, ByteArray(640))
            assertTrue(CapacityTrack.playing)
            assertEquals(1280, CapacityTrack.bytesAtPlay)
            if (Build.VERSION.SDK_INT >= 31) assertTrue(CapacityTrack.audible)
            else {
                assertFalse(CapacityTrack.audible)
                write(type, renderer, ByteArray(1280))
                assertTrue(CapacityTrack.audible)
            }
        }
    }

    @Test @Config(sdk = [33])
    fun rejectedNativeStartSetterFallsBackToTheEffectiveTwoWindowBuffer() {
        CapacityTrack.reset(capacity = 16_384)
        CapacityTrack.rejectNativeStart = true
        val reports = mutableListOf<String>()
        withCallRenderer(16_000, reports) { type, renderer ->
            assertEquals(16_384, CapacityTrack.capacity) // Allocation is preserved.
            assertEquals(1280, CapacityTrack.effective)
            write(type, renderer, ByteArray(640))
            assertFalse(CapacityTrack.audible)
            write(type, renderer, ByteArray(640))
            assertTrue(CapacityTrack.audible)
            assertTrue(reports.joinToString("\n"), reports.any {
                "callBufferRoute=effective-buffer" in it && "nativeStartFailure=" in it && "effectiveFrames=640" in it
            })
        }
    }

    @Test fun rejectedEffectiveBufferResizeRetainsPlaybackAndReportsTheFailure() {
        CapacityTrack.reset(capacity = 4096)
        CapacityTrack.rejectNativeStart = true
        CapacityTrack.rejectEffectiveBuffer = true
        val reports = mutableListOf<String>()
        withCallRenderer(16_000, reports) { type, renderer ->
            assertEquals(4096, CapacityTrack.effective)
            write(type, renderer, ByteArray(4096))
            assertFalse(CapacityTrack.blocked)
            assertTrue(CapacityTrack.audible)
            assertTrue(reports.any { "effectiveBufferFailure=" in it })
        }
    }

    private fun withCallRenderer(rate: Int, reports: MutableList<String> = mutableListOf(), block: (Class<*>, Any) -> Unit) {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }
            .apply { isAccessible = true }.newInstance(
                AudioFormat(AudioCodecKind.LPCM, rate, 1, 100, "telephony"), false, false, 0, 0,
                AudioFocusCoordinator(null, false, false), 0, 1000, { line: String -> reports += line }, 0L, false, null, false)
        try {
            type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
            block(type, renderer)
        } finally {
            (renderer as Closeable).close()
            type.getDeclaredMethod("release").apply { isAccessible = true }.invoke(renderer)
        }
    }

    private fun get(type: Class<*>, renderer: Any, name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(type: Class<*>, renderer: Any, bytes: ByteArray) =
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, bytes, 0, bytes.size)

    @Implements(AudioTrack::class)
    class CapacityTrack {
        @Implementation fun getBufferSizeInFrames() = (if (effective > 0) effective else capacity) / 2
        @Implementation(minSdk = 24) fun getBufferCapacityInFrames() = capacity / 2
        @Implementation fun setBufferSizeInFrames(frames: Int): Int {
            if (rejectEffectiveBuffer) return AudioTrack.ERROR_INVALID_OPERATION
            effective = (frames * 2).coerceIn(2, capacity)
            return effective / 2
        }
        @Implementation(minSdk = 31) fun setStartThresholdInFrames(frames: Int): Int {
            if (rejectNativeStart) return AudioTrack.ERROR_INVALID_OPERATION
            nativeStartBytes = (frames * 2).coerceIn(2, capacity)
            return nativeStartBytes / 2
        }
        @Implementation(minSdk = 31) fun getStartThresholdInFrames() = startBytes() / 2
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 1
        @Implementation fun pause() { playing = false; audible = false }
        @Implementation fun play() { playing = true; bytesAtPlay = queued; audible = queued >= startBytes() }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            val limit = if (effective > 0) effective else capacity
            if (!playing && queued + size > limit) {
                blocked = true
                return 0 // A real blocking AudioTrack.write waits here indefinitely.
            }
            if (!audible) {
                queued = minOf(queued + size, limit)
                if (playing && queued >= startBytes()) audible = true
            }
            return size
        }
        companion object {
            var capacity = 0
            var queued = 0
            var playing = true
            var blocked = false
            var bytesAtPlay = 0
            var minimum = 128
            var effective = 0
            var nativeStartBytes = Int.MAX_VALUE
            var audible = false
            var rejectNativeStart = false
            var rejectEffectiveBuffer = false
            fun startBytes() = minOf(nativeStartBytes, if (effective > 0) effective else capacity)
            fun reset(capacity: Int, minimum: Int = 128) {
                this.capacity = capacity
                this.minimum = minimum
                effective = capacity
                nativeStartBytes = capacity
                audible = false
                rejectNativeStart = false
                rejectEffectiveBuffer = false
                queued = 0
                playing = false
                blocked = false
                bytesAtPlay = 0
            }
            @JvmStatic @Implementation
            fun getMinBufferSize(rate: Int, channels: Int, encoding: Int): Int = minimum
        }
    }
}
