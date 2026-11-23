package com.carlink.core.settings

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence

data class SystemBarVisibility(val statusBar: Boolean = false, val bottomBar: Boolean = false)

/** Shares the engine's existing preferences; its onResume applies and may rebuild the session layout. */
class SystemBarPreferences(context: Context) {
    private val app = context.applicationContext

    fun load() = SystemBarVisibility(
        statusBar = !AirPlayPersistence.loadHideTopBar(app),
        bottomBar = !AirPlayPersistence.loadHideBottomBar(app),
    )

    fun showStatusBar(visible: Boolean) = AirPlayPersistence.saveHideTopBar(app, !visible)
    fun showBottomBar(visible: Boolean) = AirPlayPersistence.saveHideBottomBar(app, !visible)
}
