package com.carlink.core.settings

import android.app.Application
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SystemBarPreferencesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun neitherBarIsShownUntilRequested() {
        assertEquals(SystemBarVisibility(), SystemBarPreferences(app).load())
    }

    @Test fun eitherBothOrNeitherRoundTripToThePreferencesReadByProjection() {
        for (top in listOf(false, true)) for (bottom in listOf(false, true)) {
            SystemBarPreferences(app).apply { showStatusBar(top); showBottomBar(bottom) }
            assertEquals(SystemBarVisibility(top, bottom), SystemBarPreferences(app).load())
            assertEquals(!top, AirPlayPersistence.loadHideTopBar(app))
            assertEquals(!bottom, AirPlayPersistence.loadHideBottomBar(app))
        }
    }

    @Test fun changingOneBarDoesNotOverwriteTheOtherOrTheChosenCarPlaySize() {
        val bars = SystemBarPreferences(app)
        AirPlayPersistence.saveWidthPhysicalMm(app, 120)
        bars.showBottomBar(true)
        bars.showStatusBar(true)
        bars.showBottomBar(false)
        assertEquals(SystemBarVisibility(true, false), bars.load())
        assertEquals(120, AirPlayPersistence.loadWidthPhysicalMm(app))
    }

    @Test fun projectionSettingsAreVisibleWhenReturningToTheHost() {
        AirPlayPersistence.saveHideTopBar(app, false)
        AirPlayPersistence.saveHideBottomBar(app, true)
        assertEquals(SystemBarVisibility(true, false), SystemBarPreferences(app).load())
    }
}
