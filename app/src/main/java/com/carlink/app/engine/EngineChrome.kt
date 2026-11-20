package com.carlink.app.engine

import android.content.Context
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.core.connection.CarLinkAppearance
import com.carlink.core.connection.CarPlaySettingsPolicy
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayHostIntegration.MenuItem

/** The engine exposes only the wired display/audio controls not owned by the app's settings. */
object EngineChrome {
    fun install(context: Context) {
        val app = context.applicationContext
        CarPlayHostIntegration.menuItems = setOf(
            MenuItem.SIDE_PANEL, MenuItem.AUDIO_MAPPING, MenuItem.VEHICLE_IDENTITY,
            MenuItem.CAR_BUTTON_ICON, MenuItem.SAFE_AREA, MenuItem.HOME,
        )
        CarPlaySettingsPolicy.appearance = {
            when (UiPreferences(app).themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> CarLinkAppearance.FOLLOW_SYSTEM
                ThemeMode.DARK -> CarLinkAppearance.DARK
                ThemeMode.LIGHT -> CarLinkAppearance.LIGHT
            }
        }
    }
}
