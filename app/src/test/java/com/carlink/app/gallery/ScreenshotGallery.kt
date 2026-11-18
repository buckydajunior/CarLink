package com.carlink.app.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

private data class GalleryConfig(val name: String, val profile: UiProfile, val qualifiers: String, val scale: Float = 1f)

/** Only the screens shipped in the wired receiver, at landscape, portrait head-unit and phone sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotGallery {
    @Test fun recordGallery() {
        assumeTrue(System.getProperty("carlink.screenshots") == "true")
        val out = File(System.getProperty("carlink.repoRoot"), "docs/screenshots/f150").apply { mkdirs() }
        val only = System.getProperty("carlink.screenshots.only").orEmpty().takeIf { it.isNotBlank() }?.toRegex()
        val configs = listOf(
            GalleryConfig("f150-headunit", UiProfile.CAR, "w1024dp-h600dp-land-mdpi", 1.15f),
            GalleryConfig("f150-portrait", UiProfile.CAR, "w768dp-h1024dp-port-mdpi", 1.15f),
            GalleryConfig("f150-phone", UiProfile.HANDHELD, "w400dp-h860dp-port-xxhdpi"),
        )
        for (config in configs) {
            RuntimeEnvironment.setQualifiers(config.qualifiers)
            for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                for (entry in galleryEntries) {
                    val file = File(out, "f150_${entry.name}_${config.name}_${theme.name.lowercase()}.webp")
                    if (only != null && !only.containsMatchIn(file.name)) continue
                    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.setContent {
                                CarLinkTheme(config.profile, theme, config.scale, liteRendering = true) { entry.content() }
                            }
                        }
                        repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                        scenario.onActivity { activity ->
                            val view = activity.window.decorView
                            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bitmap))
                            val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                            file.outputStream().use { small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
                        }
                    }
                }
            }
        }
    }
}
