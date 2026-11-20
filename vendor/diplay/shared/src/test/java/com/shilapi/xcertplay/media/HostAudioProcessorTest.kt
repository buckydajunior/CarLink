package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.Closeable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/** CarLink: exercise the actual renderer-to-host PCM hook, including its original stream identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 33], manifest = Config.NONE, shadows = [HostAudioProcessorTest.CapturingTrack::class])
class HostAudioProcessorTest {
    private val renderers = mutableListOf<Any>()
    private var originalFactory: ((HostAudio.PlaybackPcmFormat) -> HostAudio.PcmProcessor?)? = null
    private var originalPlayback: ((String, ByteArray, Int, Int) -> Unit)? = null
    private var originalCallsAsMedia = false

    @Before fun isolateHooks() {
        originalFactory = HostAudio.playbackProcessorFactory
        originalPlayback = HostAudio.playbackPcm
        originalCallsAsMedia = HostAudio.callsAsMedia
        HostAudio.playbackProcessorFactory = null
        HostAudio.playbackPcm = null
        HostAudio.callsAsMedia = false
        CapturingTrack.writes.clear()
    }

    @After fun restoreHooksAndCloseTracks() {
        try {
            renderers.forEach { renderer ->
                (renderer as Closeable).close()
                // These tests deliberately run the worker's methods synchronously rather than
                // starting its thread, so its normal finally/release has not run.
                method(renderer, "release").invoke(renderer)
            }
        } finally {
            HostAudio.playbackProcessorFactory = originalFactory
            HostAudio.playbackPcm = originalPlayback
            HostAudio.callsAsMedia = originalCallsAsMedia
            CapturingTrack.writes.clear()
        }
    }

    @Test fun callRoutedAsMediaStillIdentifiesItsOriginalPhoneStream() {
        val formats = mutableListOf<HostAudio.PlaybackPcmFormat>()
        HostAudio.callsAsMedia = true
        HostAudio.playbackProcessorFactory = { format -> formats += format; null }

        val renderer = renderer(audioType = "telephony", channels = 1)

        assertEquals(AudioChannel.MEDIA, field(renderer, "mappedChannel"))
        assertEquals(listOf(HostAudio.PlaybackPcmFormat("PHONE", "telephony", 48_000, 1,
            AndroidAudioFormat.ENCODING_PCM_16BIT)), formats)
        val pcm = pcm16(16000, -16000)
        write(renderer, pcm)
        assertArrayEquals(pcm16(16000, -16000), lastWrite(renderer))
    }

    @Test fun parallelTracksHaveFreshProcessorsAndProcessOnlyTheirOutgoingRange() {
        val processors = mutableListOf<CountingProcessor>()
        HostAudio.playbackProcessorFactory = {
            CountingProcessor().also(processors::add)
        }
        val first = renderer()
        val second = renderer()
        assertEquals(2, processors.size)
        assertNotSame(processors[0], processors[1])

        val padded = pcm16(777, 777, 1000, -1000, 888, 888)
        write(first, padded, 4, 4)
        assertArrayEquals(pcm16(777, 777, 1001, -1000, 888, 888), padded)
        assertArrayEquals(pcm16(1001, -1000), lastWrite(first))
        assertEquals(listOf(4 to 4), processors[0].ranges)

        write(first, pcm16(1000, -1000))
        // A decoder's first/duplicate metadata notice must not reset a live filter's history.
        updateFormat(first, 48_000, 2, AndroidAudioFormat.ENCODING_PCM_16BIT)
        write(second, pcm16(1000, -1000))
        assertArrayEquals(pcm16(1002, -1000), lastWrite(first))
        assertArrayEquals(pcm16(1001, -1000), lastWrite(second))
        assertEquals(2, processors[0].calls)
        assertEquals(1, processors[1].calls)
        assertEquals(2, processors.size)
    }

    @Test fun changedDecoderRateOrChannelLayoutBypassesProcessorAndKeepsTheTrack() {
        var created = 0
        HostAudio.playbackProcessorFactory = { CountingProcessor().also { created++ } }
        val renderer = renderer()
        val originalTrack = field(renderer, "track")
        write(renderer, pcm16(2000, -2000))
        assertArrayEquals(pcm16(2001, -2000), lastWrite(renderer))

        updateFormat(renderer, 44_100, 2, AndroidAudioFormat.ENCODING_PCM_16BIT)
        write(renderer, pcm16(2000, -2000))
        assertArrayEquals(pcm16(2000, -2000), lastWrite(renderer))
        updateFormat(renderer, 48_000, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
        write(renderer, pcm16(2000, -2000))
        assertArrayEquals(pcm16(2000, -2000), lastWrite(renderer))
        assertEquals("Mismatched decoder layouts must not create a guessed processor", 1, created)
        assertSame(originalTrack, field(renderer, "track"))
    }

    @Test fun explicitUnsupportedDecodedEncodingCanBeDeclinedWithoutChangingPcm() {
        val formats = mutableListOf<HostAudio.PlaybackPcmFormat>()
        HostAudio.playbackProcessorFactory = { format ->
            formats += format
            if (format.encoding == AndroidAudioFormat.ENCODING_PCM_16BIT) CountingProcessor() else null
        }
        val renderer = renderer()
        updateFormat(renderer, 48_000, 2, AndroidAudioFormat.ENCODING_PCM_FLOAT)
        val bytes = byteArrayOf(0, 0, 0x80.toByte(), 0x3f, 0, 0, 0x80.toByte(), 0xbf.toByte())
        val expected = bytes.copyOf()

        write(renderer, bytes)

        assertEquals(listOf(AndroidAudioFormat.ENCODING_PCM_16BIT, AndroidAudioFormat.ENCODING_PCM_FLOAT),
            formats.map { it.encoding })
        assertNull(field(renderer, "playbackProcessor"))
        assertArrayEquals(expected, lastWrite(renderer))
    }

    @Test fun unsetFactoryPreservesPcmPlayback() {
        val renderer = renderer()
        val pcm = pcm16(32767, -32768, 1234, -5678)
        write(renderer, pcm)
        assertNull(field(renderer, "playbackProcessor"))
        assertArrayEquals(pcm16(32767, -32768, 1234, -5678), lastWrite(renderer))
    }

    private fun renderer(audioType: String = "media", channels: Int = 2): Any {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }
            .apply { isAccessible = true }.newInstance(
                AudioFormat(AudioCodecKind.LPCM, 48_000, channels, 100, audioType),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false), 14, 300,
                { _: String -> }, 0L, false, null, false)
        renderers += renderer
        method(renderer, "createTrack").invoke(renderer)
        assertNotNull("The renderer must actually prepare its output track", field(renderer, "track"))
        // Startup fading/buffering are unchanged features with their own tests. Keep this test
        // focused on the actual PCM passed from the new processor to AudioTrack.write.
        set(renderer, "fadeApplied", true)
        set(renderer, "playbackStarted", true)
        set(renderer, "clockTrim", null)
        return renderer
    }

    private fun updateFormat(renderer: Any, rate: Int, channels: Int, encoding: Int) =
        method(renderer, "updatePlaybackProcessor", Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            .invoke(renderer, rate, channels, encoding)

    private fun write(renderer: Any, data: ByteArray, offset: Int = 0, length: Int = data.size) =
        method(renderer, "writePcm", ByteArray::class.java, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!).invoke(renderer, data, offset, length)

    private fun lastWrite(renderer: Any): ByteArray =
        CapturingTrack.writes[field(renderer, "track") as AudioTrack]?.last()
            ?: error("AudioTrack.write was never called")

    private fun method(renderer: Any, name: String, vararg parameters: Class<*>) =
        renderer.javaClass.getDeclaredMethod(name, *parameters).apply { isAccessible = true }

    private fun field(renderer: Any, name: String): Any? =
        renderer.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun set(renderer: Any, name: String, value: Any?) =
        renderer.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)

    private fun pcm16(vararg samples: Int) = ByteArray(samples.size * 2).also { data ->
        samples.forEachIndexed { index, sample ->
            data[index * 2] = sample.toByte()
            data[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private class CountingProcessor : HostAudio.PcmProcessor {
        var calls = 0
        val ranges = mutableListOf<Pair<Int, Int>>()
        override fun process(data: ByteArray, offset: Int, length: Int) {
            calls++
            ranges += offset to length
            val sample = (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)
            val changed = sample + calls
            data[offset] = changed.toByte()
            data[offset + 1] = (changed shr 8).toByte()
        }
    }

    @Implements(AudioTrack::class)
    class CapturingTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun getBufferSizeInFrames(): Int = 4096
        @Implementation fun getPlaybackHeadPosition(): Int = 0
        @Implementation fun write(data: ByteArray, offset: Int, size: Int, mode: Int): Int {
            writes.getOrPut(track) { mutableListOf() }.add(data.copyOfRange(offset, offset + size))
            return size
        }
        companion object {
            val writes = mutableMapOf<AudioTrack, MutableList<ByteArray>>()
            @JvmStatic @Implementation
            fun getMinBufferSize(rate: Int, channelConfig: Int, encoding: Int): Int = 4096
        }
    }
}
