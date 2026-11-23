package com.carlink.core.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import com.shilapi.xcertplay.media.HostAudio
import java.util.Collections

/** Settings work stays off the playback thread; each renderer owns its own filter history. */
object EqualizerController {
    val frequenciesHz: List<Int> = Collections.unmodifiableList(BAND_FREQUENCIES_HZ.toList())

    @Volatile private var current = EqualizerSettings().normalized()

    /**
     * Loads ten frequency-keyed bands. The old 60/230/910/3600/14000Hz controls migrate once to
     * their nearest log-frequency centers: 63/250/1000/4000/16000Hz. Inserted bands stay flat so
     * an upgrade does not compound the old gains through five additional filters. Shifted centers
     * are an approximation, not an identical acoustic response. Enabled state is retained.
     */
    @Synchronized
    fun loadSettings(context: Context): EqualizerSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val frequencyKeys = prefs.getInt(SCHEMA_KEY, 0) >= SCHEMA_VERSION
        val settings = EqualizerSettings(
            enabled = prefs.getBoolean("enabled", false),
            bandGainsDb = BAND_FREQUENCIES_HZ.map { frequency ->
                if (frequencyKeys) prefs.getFloat(bandKey(frequency), 0f)
                else {
                    val oldIndex = LEGACY_TARGET_FREQUENCIES_HZ.indexOf(frequency)
                    if (oldIndex >= 0) prefs.getFloat("band_$oldIndex", 0f) else 0f
                }
            },
        ).normalized()
        if (!frequencyKeys) persist(prefs, settings)
        if (settings != current) current = settings
        return settings
    }

    /** Saves asynchronously and applies to playing music with a short, smooth transition. */
    @Synchronized
    fun saveSettings(context: Context, settings: EqualizerSettings): EqualizerSettings {
        val normalized = settings.normalized()
        persist(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE), normalized)
        if (normalized != current) current = normalized
        return normalized
    }

    /** Flat is unchanged playback; the driver's separate On/Off choice is preserved. */
    @Synchronized
    fun reset(context: Context): EqualizerSettings =
        saveSettings(context, EqualizerSettings(loadSettings(context).enabled))

    private fun persist(prefs: SharedPreferences, settings: EqualizerSettings) {
        val editor = prefs.edit().putBoolean("enabled", settings.enabled).putInt(SCHEMA_KEY, SCHEMA_VERSION)
        BAND_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
            editor.putFloat(bandKey(frequency), settings.bandGainsDb[index])
        }
        // The new values, marker and removal commit as one preferences edit. Old indexes are
        // never treated as ten-band indexes, even after subsequent loads or an explicit save.
        LEGACY_TARGET_FREQUENCIES_HZ.indices.forEach { editor.remove("band_$it") }
        editor.apply()
    }

    private fun bandKey(frequency: Int) = "band_hz_$frequency"

    internal fun install(context: Context) {
        loadSettings(context)
        HostAudio.playbackProcessorFactory = ::createProcessor
    }

    internal fun createProcessor(format: HostAudio.PlaybackPcmFormat): HostAudio.PcmProcessor? {
        // MEDIA alone also includes compatibility/unknown streams, and calls can be routed as media.
        // Require the original music type as well; Siri, calls, alerts, guidance and mic bypass EQ.
        if (format.audioType != "media" || format.channel != "MEDIA" ||
            format.encoding != AudioFormat.ENCODING_PCM_16BIT ||
            format.channels !in 1..2 || format.sampleRate !in 8_000..192_000) return null
        val equalizer = Pcm16Equalizer(format.sampleRate, format.channels) { current }
        val spectrum = MusicSpectrumController.createTap(format.sampleRate, format.channels)
        return object : HostAudio.PcmProcessor {
            override fun process(data: ByteArray, offset: Int, length: Int) {
                equalizer.process(data, offset, length)
                spectrum.offer(data, offset, length)
            }
        }
    }

    internal const val PREFERENCES = "carlink_music_equalizer"
    private const val SCHEMA_KEY = "schema_version"
    private const val SCHEMA_VERSION = 2
    private val LEGACY_TARGET_FREQUENCIES_HZ = intArrayOf(63, 250, 1_000, 4_000, 16_000)
}
