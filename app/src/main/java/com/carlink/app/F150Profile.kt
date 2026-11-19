package com.carlink.app

import android.content.Context
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.UnifiedSettingsStore
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis

/** One-time defaults and migrations; restarting preserves the driver's choices. */
object F150Profile {
    val carPlayWidths = listOf(280, 220, 180, 150, 120)
    const val DEFAULT_CARPLAY_WIDTH = 180

    fun install(context: Context) {
        val prefs = context.getSharedPreferences("f150_profile", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("installed", false)) {
            UiPreferences(context).apply {
                uiProfileMode = UiProfileMode.CAR
                interfaceScale = 1.15f
                renderingMode = RenderingMode.LITE
            }
            val store = UnifiedSettingsStore(context)
            store.save(store.load().copy(interfaceSize = InterfaceSize.CUSTOM))
            AirPlayPersistence.saveWidthPhysicalMm(context, DEFAULT_CARPLAY_WIDTH)
            AirPlayPersistence.savePhysicalSizeBasis(context, AirPlayPhysicalSizeBasis.WIDTH)
            prefs.edit().putBoolean("installed", true).apply()
        }
        // Earlier F-150 builds did not expose this setting and used platform AEC only.
        // Upgrade them too, without resetting display/EQ preferences or a subsequent Off choice.
        if (!prefs.getBoolean("call_echo_default_installed", false)) {
            AirPlayPersistence.saveCallEchoCancellation(context, true)
            prefs.edit().putBoolean("call_echo_default_installed", true).apply()
        }
    }
}
