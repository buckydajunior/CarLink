package com.carlink.core.audio

import java.util.Collections

/** CarPlay music only. Band order is [EqualizerController.frequenciesHz]. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = List(BAND_COUNT) { 0f },
) {
    internal fun normalized(): EqualizerSettings = EqualizerSettings(enabled,
        Collections.unmodifiableList(List(BAND_COUNT) { index ->
            bandGainsDb.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f
        }))
}

internal const val BAND_COUNT = 10
internal val BAND_FREQUENCIES_HZ = intArrayOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
