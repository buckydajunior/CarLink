package com.carlink.app

import android.app.Application
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.connection.CarPlaySettingsPolicy
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
        assertEquals(UiProfileMode.CAR, UiPreferences(app).uiProfileMode)
        assertEquals(com.carlink.app.ui.theme.RenderingMode.LITE, UiPreferences(app).renderingMode)
        assertEquals(InterfaceSize.CUSTOM, UnifiedSettingsStore(app).load().interfaceSize)
        assertTrue(AirPlayPersistence.loadWidthPhysicalMm(app) < CarPlaySettingsPolicy.widthMm(InterfaceSize.LARGE)!!)
    }

    @Test fun restartingDoesNotResetTheDriversChosenCarPlaySize() {
        F150Profile.install(app)
        AirPlayPersistence.saveWidthPhysicalMm(app, 120)
        F150Profile.install(app)
        assertEquals(120, AirPlayPersistence.loadWidthPhysicalMm(app))
    }

    @Test fun freshInstallEnablesSoftwareCallEchoCancellation() {
        F150Profile.install(app)
        assertTrue(AirPlayPersistence.loadCallEchoCancellation(app))
    }

    @Test fun existingInstallGetsCallDefaultWithoutResettingDisplay() {
        app.getSharedPreferences("f150_profile", Application.MODE_PRIVATE)
            .edit().putBoolean("installed", true).commit()
        AirPlayPersistence.saveWidthPhysicalMm(app, 120)
        AirPlayPersistence.saveCallEchoCancellation(app, false)
        F150Profile.install(app)
        assertTrue(AirPlayPersistence.loadCallEchoCancellation(app))
        assertEquals(120, AirPlayPersistence.loadWidthPhysicalMm(app))
    }

    @Test fun laterRestartsRespectTheDriversOffChoice() {
        F150Profile.install(app)
        AirPlayPersistence.saveCallEchoCancellation(app, false)
        F150Profile.install(app)
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(app))
    }
}
