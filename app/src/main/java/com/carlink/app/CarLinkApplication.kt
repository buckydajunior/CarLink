package com.carlink.app

import android.app.Application
import android.util.Log
import com.carlink.app.entry.HomeLauncher
import com.carlink.app.ui.HomeActivity
import com.carlink.app.ui.home.CarPlayWaitingContent
import com.carlink.core.connection.CarLinkConfig
import com.carlink.core.connection.CarLinkCore
import com.shilapi.xcertplay.CarPlayHostIntegration

/** No Android Auto Application, second process, floating overlay or discovery startup. */
class CarLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        F150Profile.install(this)
        com.carlink.app.ui.applyScreenType(com.carlink.app.ui.UiPreferences(this).uiProfileMode)
        CarPlayHostIntegration.homeActivity = HomeActivity::class.java
        CarPlayHostIntegration.appName = getString(R.string.app_name)
        CarPlayHostIntegration.mediaSessionTag = "F-150 CarPlay"
        CarPlayHostIntegration.toastHandler = { _, text ->
            Log.i("CarLink-EngineToast", "CarPlay engine: $text")
            true
        }
        com.shilapi.xcertplay.orchestration.CarPlayController.openCarScreen = {
            runCatching { startActivity(HomeActivity.carSettingsIntent(this)) }
                .onFailure { Log.w("CarLink", "Could not open settings: $it") }
            Unit
        }
        com.carlink.app.engine.EngineChrome.install(this)
        CarLinkCore.install(this, CarLinkConfig(
            accessoryName = getString(R.string.app_name),
            host = HomeLauncher,
            waitingView = CarPlayWaitingContent::create,
        ))
        // Size changes apply on reconnect, preserving native video resolution.
        CarPlayHostIntegration.liveSizeWidthsMm = emptyList()
        com.carlink.core.diagnostics.EventLog.add("Startup",
            "application ready; version ${BuildConfig.VERSION_NAME}; API ${android.os.Build.VERSION.SDK_INT}; " +
                "elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()}")
    }
}
