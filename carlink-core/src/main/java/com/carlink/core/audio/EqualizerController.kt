package com.carlink.core.audio

import android.content.Context
import android.media.AudioFormat
import com.shilapi.xcertplay.media.HostAudio
import java.util.Collections

/** Settings work stays off the playback thread; each renderer owns its own filter history. */
object EqualizerController {
    val frequenciesHz: List<Int> = Collections.unmodifiableList(BAND_FREQUENCIES_HZ.toList())

    @Volatile private var current = EqualizerSettings().normalized()

    @Synchronized
    fun loadSettings(context: Context): EqualizerSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val settings = EqualizerSettings(
            enabled = prefs.getBoolean("enabled", false),
            bandGainsDb = List(BAND_COUNT) { prefs.getFloat("band_$it", 0f) },
        ).normalized()
        if (settings != current) current = settings
        return settings
    }

    /** Saves asynchronously and applies to playing music with a short, smooth transition. */
    @Synchronized
    fun saveSettings(context: Context, settings: EqualizerSettings): EqualizerSettings {
        val normalized = settings.normalized()
        val editor = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", normalized.enabled)
        for (band in 0 until BAND_COUNT) editor.putFloat("band_$band", normalized.bandGainsDb[band])
        editor.apply()
        if (normalized != current) current = normalized
        return normalized
    }

    /** Flat is unchanged playback; the driver's separate On/Off choice is preserved. */
    fun reset(context: Context): EqualizerSettings = saveSettings(context, EqualizerSettings(current.enabled))

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
        return Pcm16Equalizer(format.sampleRate, format.channels) { current }
    }

    internal const val PREFERENCES = "carlink_music_equalizer"
}
