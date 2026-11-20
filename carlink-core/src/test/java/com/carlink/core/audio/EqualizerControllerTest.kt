package com.carlink.core.audio

import android.app.Application
import android.content.Context
import android.media.AudioFormat
import com.shilapi.xcertplay.media.HostAudio
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EqualizerControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        EqualizerController.loadSettings(context)
    }

    @After fun restoreHooks() { HostAudio.playbackProcessorFactory = null }

    @Test fun defaultIsOffFlatAndHasTheFiveDocumentedBands() {
        assertEquals(EqualizerSettings(), EqualizerController.loadSettings(context))
        assertEquals(listOf(60, 230, 910, 3_600, 14_000), EqualizerController.frequenciesHz)
    }

    @Test fun persistenceNormalizesGainsAndDoesNotRetainTheCallersMutableList() {
        val supplied = mutableListOf(20f, -20f, Float.NaN, Float.POSITIVE_INFINITY)
        val saved = EqualizerController.saveSettings(context, EqualizerSettings(true, supplied))
        supplied[0] = -9f
        assertEquals(EqualizerSettings(true, listOf(12f, -12f, 0f, 0f, 0f)), saved)
        assertEquals(saved, EqualizerController.loadSettings(context))
        try {
            @Suppress("UNCHECKED_CAST")
            (saved.bandGainsDb as MutableList<Float>)[0] = 5f
            fail("persisted snapshots must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun malformedPersistedGainsAreSafeAndResetRestoresBypass() {
        context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putFloat("band_0", Float.NaN).putFloat("band_1", -50f)
            .putFloat("band_4", Float.NEGATIVE_INFINITY).commit()
        assertEquals(EqualizerSettings(true, listOf(0f, -12f, 0f, 0f, 0f)), EqualizerController.loadSettings(context))
        assertEquals(EqualizerSettings(true), EqualizerController.reset(context))
        assertEquals(EqualizerSettings(true), EqualizerController.loadSettings(context))
    }

    @Test fun onlyOriginalMusicChannelsWithSupportedPcmCanGetAProcessor() {
        EqualizerController.install(context)
        val factory = requireNotNull(HostAudio.playbackProcessorFactory)
        val media = HostAudio.PlaybackPcmFormat("MEDIA", "media", 48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        assertNotNull(factory(media))
        assertNotSame(factory(media), factory(media))
        for (type in listOf("telephony", "speechrecognition", "default", "alert", "compatibility", "unknown")) {
            assertNull(type, factory(media.copy(audioType = type)))
        }
        for (channel in listOf("PHONE", "ASSISTANT", "NAVIGATION", "")) {
            assertNull(channel, factory(media.copy(channel = channel)))
        }
        assertNull(factory(media.copy(encoding = AudioFormat.ENCODING_PCM_FLOAT)))
        assertNull(factory(media.copy(channels = 0)))
        assertNull(factory(media.copy(channels = 6)))
        assertNull(factory(media.copy(sampleRate = 0)))
    }

    @Test fun settingsChangesReachExistingRenderersWithoutReadingPreferencesInTheirCallbacks() {
        EqualizerController.install(context)
        val filter = requireNotNull(HostAudio.playbackProcessorFactory?.invoke(
            HostAudio.PlaybackPcmFormat("MEDIA", "media", 48_000, 1, AudioFormat.ENCODING_PCM_16BIT)))
        val original = ByteArray(4_000) { if (it % 2 == 0) 0x10 else 0x27 } // constant 10000
        val before = original.copyOf()
        filter.process(before, 0, before.size)
        assertArrayEquals(original, before)
        EqualizerController.saveSettings(context, EqualizerSettings(true, listOf(12f, 0f, 0f, 0f, 0f)))
        val changed = original.copyOf()
        filter.process(changed, 0, changed.size)
        assertFalse(original.contentEquals(changed))
        EqualizerController.reset(context)
        filter.process(changed, 0, changed.size) // finish the transition to dry
        val after = original.copyOf()
        filter.process(after, 0, after.size)
        assertArrayEquals(original, after)
    }
}
