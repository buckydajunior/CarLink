package com.carlink.wirelesslab.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.carlink.wirelesslab.Check
import com.carlink.wirelesslab.CompatibilityReport
import com.carlink.wirelesslab.LabScreen
import com.carlink.wirelesslab.MainActivity
import com.carlink.wirelesslab.Reading
import com.carlink.wirelesslab.BluetoothAppExport
import com.carlink.wirelesslab.resetReportProviderCache
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LabGalleryTest {
    @Test fun renderNativeLayouts() {
        assumeTrue(System.getProperty("carlink.lab.screenshots") == "true")
        resetReportProviderCache()
        val out = File(System.getProperty("carlink.repoRoot"), "docs/screenshots/wireless-lab").apply { mkdirs() }
        for ((name, qualifiers) in listOf(
            "headunit" to "w1024dp-h600dp-land-mdpi",
            "portrait" to "w768dp-h1024dp-port-mdpi",
            "phone" to "w400dp-h860dp-port-xxhdpi",
        )) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val view = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as LabScreen
                shadowOf(Looper.getMainLooper()).idle()
                val decor = activity.window.decorView
                assertTrue(decor.width > 0 && decor.height > 0)
                save(decor, File(out, "lab_${name}_ready.webp"))
                val apk = File(activity.filesDir, "gallery-code.apk").apply { writeText("SYNTHETIC_CODE_FIXTURE") }
                shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
                    packageName = BluetoothAppExport.PACKAGE
                    versionName = "1.0"
                    applicationInfo = ApplicationInfo().apply {
                        packageName = BluetoothAppExport.PACKAGE
                        sourceDir = apk.path
                    }
                })
                view.onExport()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (ShadowAlertDialog.getLatestAlertDialog()?.isShowing != true && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(dialog.isShowing)
                shadowOf(Looper.getMainLooper()).idle()
                save(dialog.window!!.decorView, File(out, "lab_${name}_export.webp"))
                dialog.dismiss()
                val fixture = Check.entries.associateWith { check ->
                    when (check) {
                        Check.ANDROID -> Reading.Text("8.1.0 / API 27")
                        Check.MODEL -> Reading.Text("rk3399")
                        Check.MANUFACTURER -> Reading.Text("rockchip")
                        Check.BOARD -> Reading.Text("rk30sdk")
                        Check.HARDWARE -> Reading.Text("rk30board")
                        Check.BUILD -> Reading.Text("rk3399-userdebug 8.1.0 OPM8.190605.005 eng.zxw.20220304.174223 test-keys")
                        Check.ABI -> Reading.Text("arm64-v8a")
                        Check.BLUETOOTH_SETTINGS_HANDLER, Check.ANDROID_BLUETOOTH_PAGE ->
                            Reading.Text("com.android.settings/.Settings\$BluetoothSettingsActivity")
                        Check.BLUETOOTH_ADAPTER -> Reading.Flag(false)
                        Check.BLUETOOTH_ENABLED, Check.BONDED_COUNT -> Reading.Unavailable
                        Check.OEM_HOME -> Reading.Text("com.example.linkswell/.HomeActivity")
                        Check.OEM_APPS -> Reading.Text(activity.getString(com.carlink.wirelesslab.R.string.carlink_lab_oem_scope) +
                            "\n\nBluetooth — com.example.linkswell.bt\nVersion: 1.0 (1); system app: Yes; app enabled: Yes\n" +
                            "Service: com.example.linkswell.bt.BluetoothService; exported: No; declared enabled: Yes; permission: None declared")
                        Check.OEM_SERIAL_NODE -> Reading.Text(activity.getString(com.carlink.wirelesslab.R.string.carlink_lab_node_metadata,
                            "Yes", "No", "No"))
                        else -> Reading.Flag(true)
                    }
                }
                view.showState(CompatibilityReport(fixture).format(activity), busy = false)
                shadowOf(Looper.getMainLooper()).idle()
                save(decor, File(out, "lab_${name}_report.webp"))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                view.isSmoothScrollingEnabled = false
                view.clearFocus()
                view.scrollTo(0, view.getChildAt(0).height - view.height + view.paddingBottom)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                save(decor, File(out, "lab_${name}_report_end.webp"))
                val body = descendants(view).filterIsInstance<TextView>().single {
                    it.text.startsWith("F-150 Wireless Lab") && it.text.contains("compatibility report")
                }
                val offset = body.text.indexOf(activity.getString(com.carlink.wirelesslab.R.string.carlink_lab_oem_home))
                view.scrollTo(0, body.top + body.layout.getLineTop(body.layout.getLineForOffset(offset)))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                save(decor, File(out, "lab_${name}_oem_report.webp"))
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun save(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        file.outputStream().use { assertTrue(small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)) }
        small.recycle()
        bitmap.recycle()
    }
}
