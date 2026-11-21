package com.carlink.wirelesslab

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class SettingsReturnTest {
    private fun prepare(controller: ActivityController<MainActivity>): MainActivity {
        val activity = controller.setup().get()
        val application = ApplicationInfo().apply {
            packageName = "com.example.system.settings"
            enabled = true
        }
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = application.packageName
            applicationInfo = application
        })
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.system.settings"
                name = "com.example.system.settings.BluetoothActivity"
                exported = true
                enabled = true
                applicationInfo = application
            }
        }
        shadowOf(activity.packageManager).addOrUpdateActivity(info.activityInfo)
        shadowOf(activity.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), info)
        return activity
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun clickSettings(activity: MainActivity) {
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.carlink_lab_open_bluetooth) }.performClick()
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Worker result did not arrive")
    }

    @Test fun returningFromTheSettingsRequestRefreshesReportWithTheRequestContext() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = prepare(controller)
            clickSettings(activity)
            awaitUi { shadowOf(activity).peekNextStartedActivity() != null }
            assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(activity).nextStartedActivity.action)
            controller.pause().stop().start().resume().visible()
            awaitUi {
                descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text.contains("Last settings start request:") && it.text.contains("System Bluetooth shortcut")
                }
            }
        }
    }

    @Test fun unavailableSettingsRequestIsImmediatelyIncludedInTheShareableReport() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val app = shadowOf(RuntimeEnvironment.getApplication())
            app.checkActivities(true)
            try {
                clickSettings(activity)
                awaitUi {
                    descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.contains("Last settings start request:") &&
                            it.text.contains("No settings page accepted the request")
                    }
                }
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text == activity.getString(R.string.carlink_lab_share)
                }.isEnabled)
            } finally {
                app.checkActivities(false)
            }
        }
    }

    @Test fun leavingBeforeTheWorkerReturnsDoesNotOpenASettingsPageLater() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = prepare(controller)
            clickSettings(activity)
            controller.pause().stop()
            // The worker's result is posted to the paused main looper, after onPause marks the
            // Activity invisible. The control reenables once that result is safely discarded.
            awaitUi {
                descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text == activity.getString(R.string.carlink_lab_open_bluetooth)
                }.isEnabled
            }
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
