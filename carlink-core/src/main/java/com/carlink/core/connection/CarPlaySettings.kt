package com.carlink.core.connection

import android.content.Context
import com.carlink.core.settings.AudioDelay
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import com.carlink.core.settings.UnifiedSettings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.CarPlayNightMode
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis

enum class CarLinkAppearance { FOLLOW_SYSTEM, DARK, LIGHT }
data class CarPlayEngineSettings(
    val displayScalePercent: Int?, val fps: Int?, val hevc: Boolean?, val directVideoOutput: Boolean?,
    val widthPhysicalMm: Int?, val nightModeKey: String, val appearanceKey: String, val audioBufferMillis: Int,
)
object CarPlaySettingsPolicy {
    @Volatile var appearance: () -> CarLinkAppearance = { CarLinkAppearance.DARK }
    const val MEDIUM_WIDTH_MM = 300
    fun widthMm(size: InterfaceSize): Int? = when (size) {
        InterfaceSize.SMALL -> 350
        InterfaceSize.MEDIUM -> MEDIUM_WIDTH_MM
        InterfaceSize.LARGE -> 220
        InterfaceSize.CUSTOM -> null
    }
    fun map(settings: UnifiedSettings, picture: PictureChoice = PicturePolicy.ENGINE_DEFAULTS): CarPlayEngineSettings {
        val (scale, fps) = when (settings.picture) {
            Picture.AUTOMATIC -> picture.carPlayScalePercent to picture.carPlayFps
            Picture.BEST -> 100 to 60
            Picture.BALANCED -> 100 to 30
            Picture.LIGHT -> 75 to 30
            Picture.CUSTOM -> null to null
        }
        return CarPlayEngineSettings(scale, fps,
            if (settings.picture == Picture.AUTOMATIC) picture.carPlayHevc else null,
            if (settings.picture == Picture.AUTOMATIC) picture.carPlayDirectVideo else null,
            widthMm(settings.interfaceSize),
            when (settings.dayNight) { DayNight.AUTOMATIC -> "system"; DayNight.DAY -> "day"; DayNight.NIGHT -> "night" },
            when (runCatching { appearance() }.getOrDefault(CarLinkAppearance.DARK)) {
                CarLinkAppearance.FOLLOW_SYSTEM -> AppAppearance.AUTO.key
                CarLinkAppearance.DARK -> AppAppearance.DARK.key
                CarLinkAppearance.LIGHT -> AppAppearance.LIGHT.key
            },
            when (settings.audioDelay) { AudioDelay.LOW -> 100; AudioDelay.NORMAL -> 300; AudioDelay.HIGH -> 500 })
    }
    /** Run on the ordered session worker; the host reads these before USB bring-up. */
    fun apply(context: Context, values: CarPlayEngineSettings) {
        values.displayScalePercent?.let { AirPlayPersistence.saveDisplayScalePercent(context, it) }
        values.fps?.let { AirPlayPersistence.saveFps(context, it) }
        values.hevc?.let { AirPlayPersistence.saveHevcEnabled(context, it) }
        values.directVideoOutput?.let { AirPlayPersistence.saveDirectVideoOutput(context, it) }
        values.widthPhysicalMm?.let { AirPlayPersistence.saveWidthPhysicalMm(context, it) }
        AirPlayPersistence.savePhysicalSizeBasis(context, AirPlayPhysicalSizeBasis.WIDTH)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.fromKey(values.nightModeKey))
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.fromKey(values.appearanceKey))
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = values.audioBufferMillis)
    }
}
