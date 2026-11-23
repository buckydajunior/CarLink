package com.carlink.core.connection

import android.app.Application
import android.content.Context
import android.view.View
import com.carlink.core.audio.EqualizerController
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.entry.UsbWatcher
import com.shilapi.xcertplay.AirPlayPersistence

interface CarLinkHost {
    enum class OpenReason { USB_ATTACHED, UNPLUGGED }
    fun openHome(context: Context, reason: OpenReason)
}

/** The wired receiver's host screen, accessory name and lightweight waiting-view factory. */
class CarLinkConfig(val accessoryName: String, val host: CarLinkHost, val waitingView: ((Context) -> View)? = null)

object CarLinkCore {
    @Volatile private var installed: CarLinkConfig? = null
    val config: CarLinkConfig get() = installed ?: error("CarLinkCore.install must run from Application.onCreate")

    fun install(app: Application, config: CarLinkConfig) {
        installed = config
        EventLog.attachFile(java.io.File(app.filesDir, "logs"))
        com.carlink.core.detect.UsbAttachTimes.watch(app)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        AirPlayPersistence.saveLocationReportingEnabled(app, false)
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        if (AirPlayPersistence.loadManufacturer(app) == AirPlayPersistence.DEFAULT_MANUFACTURER) {
            AirPlayPersistence.saveManufacturer(app, config.accessoryName)
        }
        if (AirPlayPersistence.loadModel(app) == AirPlayPersistence.DEFAULT_MODEL) {
            AirPlayPersistence.saveModel(app, config.accessoryName)
        }
        if (AirPlayPersistence.loadOemLabel(app) == AirPlayPersistence.DEFAULT_OEM_LABEL) {
            AirPlayPersistence.saveOemLabel(app, config.accessoryName)
        }
        CarPlayAdapter.install(app)
        EqualizerController.install(app)
        CarPlayWaitingView.register(config.waitingView)
        UsbWatcher.get(app).start()
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                    level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) DeviceCapabilities.trimCaches()
            }
            override fun onConfigurationChanged(configuration: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() = DeviceCapabilities.trimCaches()
        })
        // Probe codecs only when a USB session needs them, on its settings worker. Some
        // head-unit media services are still starting when this process wakes without a phone.
    }
}
