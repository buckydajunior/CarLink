package com.carlink.wirelesslab.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.carlink.wirelesslab.Check
import com.carlink.wirelesslab.CompatibilityReport
import com.carlink.wirelesslab.LabScreen
import com.carlink.wirelesslab.MainActivity
import com.carlink.wirelesslab.Reading
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LabGalleryTest {
    @Test fun renderNativeLayouts() {
        assumeTrue(System.getProperty("carlink.lab.screenshots") == "true")
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
            }
        }
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
