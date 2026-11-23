package com.shilapi.xcertplay.media

/**
 * CarLink: new file. Audio hooks a host app sets; standalone DiPlay leaves them unset and plays and
 * records exactly as upstream.
 */
object HostAudio {
    // CarLink: a fresh processor per renderer prevents parallel tracks sharing filter history.
    data class PlaybackPcmFormat(
        val channel: String,
        val audioType: String,
        val sampleRate: Int,
        val channels: Int,
        val encoding: Int,
    )

    fun interface PcmProcessor {
        fun process(data: ByteArray, offset: Int, length: Int)
    }

    /**
     * CarLink: optional in-place playback DSP. The original channel/type are supplied before
     * call-as-media routing. Factories may return null for speech or unsupported PCM formats.
     * Called only when a renderer is created or its decoded format changes, never per buffer.
     */
    @Volatile
    @JvmStatic
    var playbackProcessorFactory: ((PlaybackPcmFormat) -> PcmProcessor?)? = null

    /**
     * Scales played PCM in place before it is written to the track. The first argument is the
     * stream's own channel (`AudioChannel` name: MEDIA, PHONE, ASSISTANT, NAVIGATION), also when a
     * call plays as media.
     */
    @Volatile
    @JvmStatic
    var playbackPcm: ((channel: String, data: ByteArray, offset: Int, length: Int) -> Unit)? = null

    /** Scales each microphone frame in place before it is encoded and sent to the iPhone. */
    @Volatile
    @JvmStatic
    var microphonePcm: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null

    /** Plays CarPlay calls as media (the path a BYD with its Bluetooth suspended already uses). */
    @Volatile
    @JvmStatic
    var callsAsMedia: Boolean = false
}
