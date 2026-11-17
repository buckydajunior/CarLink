package com.carlink.app

import android.content.Context
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.CarLinkPreferences
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.UnifiedSettingsStore
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis

/** Defaults for the 2018 F-150 Lariat's Linkswell. Applied once, before any USB session starts. */
object F150Profile {
    val carPlayWidths = listOf(280, 220, 180, 150, 120)
    const val DEFAULT_CARPLAY_WIDTH = 180

    fun install(context: Context) {
        val prefs = context.getSharedPreferences("f150_profile", Context.MODE_PRIVATE)
        if (prefs.getBoolean("installed", false)) return
        UiPreferences(context).apply {
            setupDone = true
            uiProfileMode = UiProfileMode.CAR
            interfaceScale = 1.15f
            renderingMode = com.carlink.app.ui.theme.RenderingMode.LITE
            floatingSwitcher = false
            autoCheckUpdates = false
        }
        CarLinkPreferences(context).apply {
            autoConnectUsb = true
            autoConnectBluetooth = false
            startOnBoot = false
            autoConnectPaused = false
        }
        com.carlink.core.settings.CarBrandStore(context).set(CarBrand.FORD)
        val store = UnifiedSettingsStore(context)
        store.save(store.load().copy(
            interfaceSize = InterfaceSize.CUSTOM,
            soundThroughCarBluetooth = false,
            sendCarLocation = false,
            bydDirections = false,
            showAdvanced = false,
        ))
        AirPlayPersistence.saveWidthPhysicalMm(context, DEFAULT_CARPLAY_WIDTH)
        AirPlayPersistence.savePhysicalSizeBasis(context, AirPlayPhysicalSizeBasis.WIDTH)
        prefs.edit().putBoolean("installed", true).apply()
    }
}
