package com.carlink.core.connection

/** Decoder-guided choices for the projected CarPlay picture, independent of app UI density. */
data class PictureChoice(
    val carPlayScalePercent: Int,
    val carPlayFps: Int,
    val carPlayHevc: Boolean?,
    val summary: String,
    val carPlayDirectVideo: Boolean? = null,
)
object PicturePolicy {
    val CARPLAY_SCALES = listOf(100, 75, 50)
    private const val SOFTWARE_MAX_PIXELS = 1280L * 720L
    private const val SLOW_HARDWARE_MAX_PIXELS = 1600L * 900L
    private const val CONSTRAINED_MAX_PIXELS = 1280L * 720L
    val ENGINE_DEFAULTS = PictureChoice(100, 30, null, "engine defaults")
    fun probeSizes(panel: VideoSize): Set<VideoSize> = CARPLAY_SCALES.map { panel.scaled(it) }.toSet()
    fun choose(caps: DeviceCapabilities): PictureChoice {
        val slow = caps.signals.liteRendering
        val defaults = ENGINE_DEFAULTS.copy(carPlayHevc = if (caps.hardwareHevc) null else false,
            carPlayDirectVideo = if (slow) true else null)
        val avc = caps.h264
        if (avc == null || avc.maxFps.isEmpty()) return defaults.copy(summary = "no H.264 decoder answered; engine defaults")
        val scale = CARPLAY_SCALES.firstOrNull { percent ->
            caps.panel.scaled(percent).let { size ->
                avc.carries(size) && (avc.hardware || size.pixels <= SOFTWARE_MAX_PIXELS) &&
                    (!slow || size.pixels <= SLOW_HARDWARE_MAX_PIXELS) &&
                    (!caps.signals.constrained || size.pixels <= CONSTRAINED_MAX_PIXELS)
            }
        } ?: CARPLAY_SCALES.last()
        val fps = if (avc.hardware && !slow && !caps.signals.lowEnd && avc.carries(caps.panel.scaled(scale), 60)) 60 else 30
        return defaults.copy(carPlayScalePercent = scale, carPlayFps = fps,
            summary = "CarPlay $scale% of ${caps.panel}, $fps fps; decoder ${avc.name}" + if (slow) "; direct video output" else "")
    }
}
