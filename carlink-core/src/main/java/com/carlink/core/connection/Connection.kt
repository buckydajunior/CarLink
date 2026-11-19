package com.carlink.core.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.carlink.core.entry.UsbWatcher
import com.carlink.core.settings.UnifiedSettingsStore
import java.util.concurrent.Executors

/** The main process owns one wired session manager and one ordered settings worker. */
object Connection {
    @Volatile private var instance: SessionManager? = null
    fun get(context: Context): SessionManager = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }
    fun onHomeShown(context: Context) = UsbWatcher.get(context).onHomeShown()

    private fun create(app: Context) = SessionManager(
        adapter = CarPlayAdapter(app),
        environment = DeviceEnvironment(app),
        applySettings = {
            val started = android.os.SystemClock.uptimeMillis()
            val settings = UnifiedSettingsStore(app).load()
            var picture = if (settings.picture == com.carlink.core.settings.Picture.AUTOMATIC) {
                runCatching { PicturePolicy.choose(DeviceCapabilities.read(app)) }.getOrDefault(PicturePolicy.ENGINE_DEFAULTS)
            } else PicturePolicy.ENGINE_DEFAULTS
            if (settings.picture == com.carlink.core.settings.Picture.AUTOMATIC) StereoQuirks.current()?.let { picture = it.apply(picture) }
            CarPlaySettingsPolicy.apply(app, CarPlaySettingsPolicy.map(settings, picture))
            com.carlink.core.diagnostics.EventLog.add("CarLink", "CarPlay settings written in ${android.os.SystemClock.uptimeMillis() - started} ms")
        },
        worker = Executors.newSingleThreadExecutor { Thread(it, "carlink-session") },
        main = Handler(Looper.getMainLooper()),
        profiles = ConnectionProfiles.sharedPreferences(app),
    )
}
