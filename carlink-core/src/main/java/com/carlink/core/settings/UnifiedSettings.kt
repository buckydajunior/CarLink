package com.carlink.core.settings

import android.content.Context

/** Wired projection settings; custom size leaves the exact engine width chosen by the driver intact. */
data class UnifiedSettings(
    val picture: Picture = Picture.AUTOMATIC,
    val interfaceSize: InterfaceSize = InterfaceSize.CUSTOM,
    val dayNight: DayNight = DayNight.AUTOMATIC,
    val audioDelay: AudioDelay = AudioDelay.NORMAL,
)
enum class Picture { AUTOMATIC, BEST, BALANCED, LIGHT, CUSTOM }
enum class InterfaceSize { SMALL, MEDIUM, LARGE, CUSTOM }
enum class DayNight { AUTOMATIC, DAY, NIGHT }
enum class AudioDelay { LOW, NORMAL, HIGH }

class UnifiedSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_unified", Context.MODE_PRIVATE)
    fun load() = UnifiedSettings(
        picture = enumOr(prefs.getString("picture", null), Picture.AUTOMATIC),
        interfaceSize = enumOr(prefs.getString("interface_size", null), InterfaceSize.CUSTOM),
        dayNight = enumOr(prefs.getString("day_night", null), DayNight.AUTOMATIC),
        audioDelay = enumOr(prefs.getString("audio_delay", null), AudioDelay.NORMAL),
    )
    fun save(settings: UnifiedSettings) {
        prefs.edit().putString("picture", settings.picture.name).putString("interface_size", settings.interfaceSize.name)
            .putString("day_night", settings.dayNight.name).putString("audio_delay", settings.audioDelay.name).apply()
    }
    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)
}
