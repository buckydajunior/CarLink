package com.carlink.core.settings
import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SettingsStoresTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun wiredSettingsRoundTripAndCustomWidthIsTheDefault() {
        val store = UnifiedSettingsStore(app)
        assertEquals(InterfaceSize.CUSTOM, store.load().interfaceSize)
        val settings = UnifiedSettings(Picture.LIGHT, InterfaceSize.LARGE, DayNight.DAY, AudioDelay.LOW)
        store.save(settings)
        assertEquals(settings, UnifiedSettingsStore(app).load())
    }
    @Test fun removedOrUnknownLegacyEnumValuesDoNotCrashStartup() {
        app.getSharedPreferences("carlink_unified", Context.MODE_PRIVATE).edit().putString("day_night", "SCHEDULE")
            .putString("picture", "UNKNOWN").apply()
        assertEquals(UnifiedSettings(), UnifiedSettingsStore(app).load())
    }
}
