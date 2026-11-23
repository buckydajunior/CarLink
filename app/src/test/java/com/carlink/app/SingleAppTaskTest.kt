package com.carlink.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29], application = Application::class)
class SingleAppTaskTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun onlyOneActivityIsExposedInTheAppLauncher() {
        val launchers = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0)
        assertEquals(listOf("com.carlink.app.ui.HomeActivity"), launchers.map { it.activityInfo.name })
    }

    @Test fun projectionHasNoExtraRecentsCardAndSettingsCannotClearItsTask() {
        val pm = app.packageManager
        val home = pm.getActivityInfo(ComponentName(app, "com.carlink.app.ui.HomeActivity"), 0)
        val car = pm.getActivityInfo(ComponentName(app, "com.shilapi.xcertplay.CarPlayHostActivity"), 0)
        assertTrue(car.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertFalse(car.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, home.launchMode)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, car.launchMode)
        assertNotEquals("Home singleTask must not clear projection when opening Settings", home.taskAffinity, car.taskAffinity)
    }

    @Test fun earlyUsbDispatchIsAvailableWhilePrivateComponentsWaitForUnlock() {
        val pm = app.packageManager
        val router = pm.getActivityInfo(ComponentName(app, "com.carlink.core.entry.UsbRouterActivity"), 0)
        val wake = pm.getReceiverInfo(ComponentName(app, "com.carlink.core.entry.WiredWakeReceiver"), 0)
        val recorder = pm.getProviderInfo(ComponentName(app, "com.shilapi.xcertplay.CrashRecordInstaller"), 0)
        assertTrue(router.directBootAware)
        assertFalse(wake.directBootAware)
        assertFalse(recorder.directBootAware)
        assertFalse(recorder.exported)
        assertEquals("${app.packageName}.crash-record", recorder.authority)
    }
}
