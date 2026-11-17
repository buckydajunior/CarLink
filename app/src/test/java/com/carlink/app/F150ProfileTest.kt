package com.carlink.app

import android.app.Application
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.connection.CarPlaySettingsPolicy
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.CarBrandStore
import com.carlink.core.settings.CarLinkPreferences
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.UnifiedSettingsStore
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class F150ProfileTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun firstLaunchIsReadyForUsbWithoutTheWelcomeWizard() {
        F150Profile.install(app)
        assertTrue(UiPreferences(app).setupDone)
        assertFalse(UiPreferences(app).floatingSwitcher)
        assertFalse(UiPreferences(app).autoCheckUpdates)
        assertEquals(UiProfileMode.CAR, UiPreferences(app).uiProfileMode)
        assertTrue(CarLinkPreferences(app).autoConnectUsb)
        assertFalse(CarLinkPreferences(app).autoConnectBluetooth)
        assertFalse(CarLinkPreferences(app).startOnBoot)
        assertEquals(CarBrand.FORD, CarBrandStore(app).chosen())
        assertEquals(InterfaceSize.CUSTOM, UnifiedSettingsStore(app).load().interfaceSize)
        assertTrue(AirPlayPersistence.loadWidthPhysicalMm(app) < CarPlaySettingsPolicy.widthMm(InterfaceSize.LARGE)!!)
    }

    @Test fun restartingDoesNotResetTheDriversChosenCarPlaySize() {
        F150Profile.install(app)
        AirPlayPersistence.saveWidthPhysicalMm(app, 120)
        F150Profile.install(app)
        assertEquals(120, AirPlayPersistence.loadWidthPhysicalMm(app))
    }
}
