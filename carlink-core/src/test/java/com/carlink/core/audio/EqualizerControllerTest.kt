package com.carlink.core.audio

import android.app.Application
import android.content.Context
import android.media.AudioFormat
import com.shilapi.xcertplay.media.HostAudio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
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

    @Test fun defaultIsOffFlatAndHasTheTenDocumentedBands() {
        assertEquals(EqualizerSettings(), EqualizerController.loadSettings(context))
        assertEquals(listOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000), EqualizerController.frequenciesHz)
        assertEquals(10, EqualizerController.loadSettings(context).bandGainsDb.size)
    }

    @Test fun persistenceNormalizesGainsAndDoesNotRetainTheCallersMutableList() {
        val supplied = mutableListOf(20f, -20f, Float.NaN, Float.POSITIVE_INFINITY)
        val saved = EqualizerController.saveSettings(context, EqualizerSettings(true, supplied))
        supplied[0] = -9f
        assertEquals(EqualizerSettings(true, List(10) { when (it) { 0 -> 12f; 1 -> -12f; else -> 0f } }), saved)
        assertEquals(saved, EqualizerController.loadSettings(context))
        try {
            @Suppress("UNCHECKED_CAST")
            (saved.bandGainsDb as MutableList<Float>)[0] = 5f
            fail("persisted snapshots must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun malformedPersistedGainsAreSafeAndResetRestoresBypass() {
        context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putFloat("band_hz_32", Float.NaN).putFloat("band_hz_63", -50f)
            .putFloat("band_hz_16000", Float.NEGATIVE_INFINITY).commit()
        assertEquals(EqualizerSettings(true, List(10) { if (it == 1) -12f else 0f }), EqualizerController.loadSettings(context))
        assertEquals(EqualizerSettings(true), EqualizerController.reset(context))
        assertEquals(EqualizerSettings(true), EqualizerController.loadSettings(context))
    }

    @Test fun legacyFiveBandsMigrateToNearestCentersLeavingInsertedBandsFlat() {
        val prefs = context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE)
        val oldGains = listOf(6f, -3f, 4f, 2f, -8f)
        for (enabled in listOf(false, true)) {
            val editor = prefs.edit().clear().putBoolean("enabled", enabled)
            oldGains.forEachIndexed { index, value -> editor.putFloat("band_$index", value) }
            editor.commit()
            val migrated = EqualizerController.loadSettings(context)
            assertEquals(EqualizerSettings(enabled, listOf(0f, 6f, 0f, -3f, 0f, 4f, 0f, 2f, 0f, -8f)), migrated)
            assertEquals(2, prefs.getInt("schema_version", 0))
            for (index in oldGains.indices) assertFalse(prefs.contains("band_$index"))
            EqualizerController.frequenciesHz.forEachIndexed { index, frequency ->
                assertEquals(migrated.bandGainsDb[index], prefs.getFloat("band_hz_$frequency", Float.NaN), 0f)
            }
            assertEquals("Subsequent loads must not migrate again", migrated, EqualizerController.loadSettings(context))
        }
    }

    @Test fun legacyInvalidAndMissingGainsNormalizeDuringMigration() {
        val prefs = context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", true)
            .putFloat("band_0", 50f).putFloat("band_1", -50f)
            .putFloat("band_2", Float.NaN).putFloat("band_3", Float.POSITIVE_INFINITY).commit()
        assertEquals(EqualizerSettings(true, listOf(0f, 12f, 0f, -12f, 0f, 0f, 0f, 0f, 0f, 0f)),
            EqualizerController.loadSettings(context))
    }

    @Test fun frequencyKeyedSaveRoundTripsEveryBandAndIgnoresStaleLegacyIndexes() {
        val prefs = context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE)
        val tenBands = EqualizerSettings(true, List(10) { it - 4.5f })
        assertEquals(tenBands, EqualizerController.saveSettings(context, tenBands))
        prefs.edit().putFloat("band_0", -12f).putFloat("band_4", 12f).commit()
        assertEquals(tenBands, EqualizerController.loadSettings(context))
        EqualizerController.saveSettings(context, tenBands)
        assertFalse(prefs.contains("band_0"))
        assertFalse(prefs.contains("band_4"))
        assertEquals(EqualizerSettings(true), EqualizerController.reset(context))
        assertEquals(EqualizerSettings(true), EqualizerController.loadSettings(context))
    }

    @Test fun resetBeforeLoadPreservesTheStoredLegacyOnOffChoice() {
        val prefs = context.getSharedPreferences(EqualizerController.PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", true).putFloat("band_0", 6f).commit()
        assertEquals(EqualizerSettings(true), EqualizerController.reset(context))
        assertEquals(EqualizerSettings(true), EqualizerController.loadSettings(context))
        assertEquals(0f, prefs.getFloat("band_hz_63", Float.NaN), 0f)
        assertEquals(2, prefs.getInt("schema_version", 0))
        assertFalse(prefs.contains("band_0"))
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
        EqualizerController.saveSettings(context, EqualizerSettings(true, List(10) { if (it == 1) 12f else 0f }))
        val changed = original.copyOf()
        filter.process(changed, 0, changed.size)
        assertFalse(original.contentEquals(changed))
        EqualizerController.reset(context)
        filter.process(changed, 0, changed.size) // finish the transition to dry
        val after = original.copyOf()
        filter.process(after, 0, after.size)
        assertArrayEquals(original, after)
    }

    @Test fun actualMusicWrapperReportsPostEqPcmAndKeepsOffAndFlatByteExact() = runBlocking {
        val format = HostAudio.PlaybackPcmFormat("MEDIA", "media", 48_000, 1, AudioFormat.ENCODING_PCM_16BIT)
        val original = ByteArray(MusicSpectrumMonitor.windowFrames(48_000) * 2).also { bytes ->
            for (frame in 0 until bytes.size / 2) {
                val value = (1_000 * sin(2 * PI * 1_000 * frame / 48_000)).roundToInt()
                bytes[frame * 2] = value.toByte()
                bytes[frame * 2 + 1] = (value shr 8).toByte()
            }
        }
        val boosted = EqualizerSettings(true, List(10) { if (it == 5) 6f else 0f })
        for (settings in listOf(boosted.copy(enabled = false), EqualizerSettings(true), boosted)) {
            EqualizerController.saveSettings(context, settings)
            MusicSpectrumController.observe().use {
                val processor = requireNotNull(EqualizerController.createProcessor(format))
                val padded = ByteArray(original.size + 10) { 0x55 }
                original.copyInto(padded, 5)
                processor.process(padded, 5, original.size)
                val played = padded.copyOfRange(5, original.size + 5)
                assertEquals(0x55.toByte(), padded[4])
                assertEquals(0x55.toByte(), padded[original.size + 5])
                if (settings == boosted) assertFalse(original.contentEquals(played))
                else assertArrayEquals(original, played)
                val snapshot = withTimeout(5_000) {
                    MusicSpectrumController.spectrum.first { it.hasAudio && it.levelsDbFs[5] > -55f }
                }
                val expected = MusicSpectrumTransform(48_000, 1).analyze(played)
                assertEquals("The wrapper must tap the actual post-EQ samples", expected, snapshot.levelsDbFs)
                if (settings == boosted) {
                    val before = MusicSpectrumTransform(48_000, 1).analyze(original)
                    assertEquals(6.0, (snapshot.levelsDbFs[5] - before[5]).toDouble(), 0.08)
                }
            }
            assertFalse(MusicSpectrumController.spectrum.value.hasAudio)
        }
    }
}
