package com.carlink.core.audio

import java.util.Collections

/** CarPlay music only. Band order is [EqualizerController.frequenciesHz]. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
) {
    internal fun normalized(): EqualizerSettings = EqualizerSettings(enabled,
        Collections.unmodifiableList(List(BAND_COUNT) { index ->
            bandGainsDb.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f
        }))
}

internal const val BAND_COUNT = 5
internal val BAND_FREQUENCIES_HZ = intArrayOf(60, 230, 910, 3_600, 14_000)
