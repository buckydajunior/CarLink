package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `non telephony prompts retain the existing buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test fun `telephony starts after twenty milliseconds or the device minimum`() {
        for (rate in listOf(8_000, 16_000, 48_000)) for (channels in listOf(1, 2)) {
            val packet = rate * channels * 2 / 50
            val plan = MediaAudioBuffer.plan(false, rate, channels, 128, 1000, isTelephony = true)
            assertEquals(packet, plan.startBytes)
            assertEquals(16 * 1024, plan.trackBufferBytes) // Retain capacity; shorten only the initial batch.
            assertEquals(packet * 2, MediaAudioBuffer.plan(false, rate, channels, packet * 2, 1000,
                isTelephony = true).startBytes)
        }
    }

    @Test fun `routing telephony as media never adds the music buffer`() {
        val call = MediaAudioBuffer.plan(true, 16_000, 1, 128, 1000, isTelephony = true)
        assertEquals(640, call.startBytes)
        assertEquals(16 * 1024, call.trackBufferBytes)
        // Navigation, Siri and music keep the existing defaults when no telephony type is supplied.
        assertEquals(4096, MediaAudioBuffer.plan(false, 16_000, 1, 128, 1000).startBytes)
        assertEquals(32_000, MediaAudioBuffer.plan(true, 16_000, 1, 128, 1000).startBytes)
    }

    @Test fun `call write chunks fit smaller granted tracks and stay pcm frame aligned`() {
        assertEquals(640, MediaAudioBuffer.callWriteChunkBytes(16_000, 1, 16_384))
        assertEquals(128, MediaAudioBuffer.callWriteChunkBytes(16_000, 1, 256))
        assertEquals(2, MediaAudioBuffer.callWriteChunkBytes(16_000, 1, 2))
        assertEquals(640, MediaAudioBuffer.callWriteChunkBytes(16_000, 1, 0))
        assertEquals(440, MediaAudioBuffer.callWriteChunkBytes(11_025, 1, 16_384))
    }

    @Test
    fun `wired presets step by 50 ms up to 200 ms, then by 100 ms up to 1000 ms`() {
        assertEquals(listOf(50, 100, 150, 200, 300, 400, 500, 600, 700, 800, 900, 1000), MediaAudioBuffer.wiredPresets)
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.wiredPresets[4])
        assertEquals(MediaAudioBuffer.MOST_STABLE_MILLIS, MediaAudioBuffer.wiredPresets.last())
    }

    @Test
    fun `wireless keeps the classic presets`() {
        assertEquals(listOf(300, 500, 1000), MediaAudioBuffer.wirelessPresets)
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitizeWireless(50))
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitizeWireless(400))
        assertEquals(50, MediaAudioBuffer.sanitizeWired(50))
    }

    @Test
    fun `delays saved by earlier versions stay selected`() {
        for (millis in listOf(300, 500, 1000)) {
            assertEquals(millis, MediaAudioBuffer.sanitize(millis))
            assertEquals(millis, MediaAudioBuffer.sanitizeWireless(millis))
            assertEquals(millis, MediaAudioBuffer.sanitizeWired(millis))
        }
    }

    @Test
    fun `shortest music buffer still keeps the low-latency floor and headroom`() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 50)
        assertEquals(9_600, plan.startBytes)
        assertEquals(48_000, plan.trackBufferBytes)
        // A device minimum above the preset wins over it.
        assertEquals(15_360, MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 15_360, mediaMillis = 50).startBytes)
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }
}
