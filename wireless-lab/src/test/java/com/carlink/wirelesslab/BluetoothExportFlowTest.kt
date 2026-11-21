package com.carlink.wirelesslab

import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class BluetoothExportFlowTest {
    @Before fun resetProviderRoots() = resetReportProviderCache()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun install() {
        val apk = File(context.filesDir, "factory-bluetooth.apk").apply { writeText("APK_CODE") }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = BluetoothAppExport.PACKAGE
            versionName = "1.0"
            applicationInfo = ApplicationInfo().apply {
                packageName = BluetoothAppExport.PACKAGE
                sourceDir = apk.path
            }
        })
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun exportButton(activity: MainActivity) = descendants(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text == activity.getString(R.string.carlink_lab_export) }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Worker result did not arrive")
    }

    @Test fun explicitExportOffersScopedZipSharingWithoutNeedingARadioReport() {
        install()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            exportButton(activity).performClick()
            awaitUi { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("application/zip", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("content", send.clipData!!.getItemAt(0).uri.scheme)
            assertEquals("${context.packageName}.reports", send.clipData!!.getItemAt(0).uri.authority)
            assertNull(send.component)
        }
    }

    @Test fun pausedActivityDoesNotOpenADelayedExportDialogOrChooser() {
        install()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            exportButton(activity).performClick()
            controller.pause().stop()
            awaitUi { exportButton(activity).isEnabled }
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun missingPackageShowsFailureWithoutOpeningOtherApps() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            exportButton(activity).performClick()
            awaitUi {
                descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text == activity.getString(R.string.carlink_lab_export_missing)
                }
            }
            assertTrue(exportButton(activity).isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun saveRetainsPreparedFileAcrossActivityRecreationAndWritesSelectedDestination() {
        install()
        val state = Bundle()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            exportButton(activity).performClick()
            awaitUi { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("application/zip", request.intent.type)
            controller.saveInstanceState(state)
        }
        val bytes = ByteArrayOutputStream()
        val destination = Uri.parse("content://test.documents/new-copy")
        shadowOf(context.contentResolver).registerOutputStream(destination, bytes)
        Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible().use { controller ->
            val activity = controller.get()
            // Deliver the framework callback after process-style recreation, with the saved filename.
            val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            callback.isAccessible = true
            callback.invoke(activity, 2, Activity.RESULT_OK, Intent().setData(destination))
            awaitUi {
                descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text == activity.getString(R.string.carlink_lab_export_saved)
                }
            }
            val archive = File(context.cacheDir, "reports").listFiles()!!.single { it.extension == "zip" }
            assertArrayEquals(archive.readBytes(), bytes.toByteArray())
        }
    }
}
