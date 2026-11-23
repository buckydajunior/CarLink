package com.carlink.app.ui.wired

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.core.audio.EqualizerSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1024dp-h1200dp-mdpi")
class EqualizerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(EqualizerSettings(true))
    private val commits = mutableListOf<Pair<Int, Float>>()

    private fun show() {
        compose.setContent {
            CarLinkTheme(UiProfile.CAR, ThemeMode.DARK, liteRendering = true) {
                EqualizerContent(state.value,
                    onBand = { band, gain ->
                        commits += band to gain
                        state.value = state.value.copy(bandGainsDb = state.value.bandGainsDb.mapIndexed { index, old ->
                            if (band == index) gain else old
                        })
                    },
                    onReset = { state.value = EqualizerSettings(state.value.enabled) },
                )
            }
        }
    }

    @Test fun selectingBandLabelsTheLargeControlAndCommitsTheCorrectFrequency() {
        show()
        compose.onNodeWithContentDescription("Treble, 8 kHz, +0 dB").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Treble, 8 kHz").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle {
            assertEquals(listOf(8 to 4f), commits)
            assertEquals(4f, state.value.bandGainsDb[8])
            assertEquals(0f, state.value.bandGainsDb[1])
        }
        compose.onNodeWithContentDescription("Treble, 8 kHz, +4 dB").assertExists()
    }

    @Test fun graphDragUpdatesOneBandAndCommitsOnceOnRelease() {
        show()
        compose.onNodeWithContentDescription(CHART).performScrollTo().performTouchInput {
            val start = Offset(width * 0.15f, height * 0.5f)
            down(start)
            moveTo(start - Offset(0f, height * 0.25f), delayMillis = 200)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals(1, commits.single().first)
            assertTrue(commits.single().second in 2f..8f)
            assertTrue(state.value.bandGainsDb.filterIndexed { index, _ -> index != 1 }.all { it == 0f })
        }
    }

    @Test fun canceledGraphDragRestoresThePreviewWithoutSaving() {
        show()
        compose.onNodeWithContentDescription(CHART).performScrollTo().performTouchInput {
            val start = Offset(width * 0.15f, height * 0.5f)
            down(start)
            moveTo(start - Offset(0f, height * 0.25f), delayMillis = 200)
            cancel()
        }
        compose.onNodeWithContentDescription("Bass, 63 Hz, +0 dB").assertExists()
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
    }

    @Test fun switchingBandDuringSliderGestureNeverCommitsIntoTheNewBand() {
        show()
        compose.onNodeWithContentDescription("Bass, 63 Hz").performScrollTo().performTouchInput {
            down(Offset(width * 0.5f, height * 0.5f))
            moveTo(Offset(width * 0.75f, height * 0.5f), delayMillis = 200)
        }
        compose.onNodeWithText("Next band").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("An old gesture must never target the newly selected125Hz", commits.none { it.first == 2 })
            assertEquals(0f, state.value.bandGainsDb[2])
        }
    }

    @Test fun offKeepsStoredLevelsAndDisablesEditing() {
        state.value = EqualizerSettings(false, List(10) { if (it == 1) 3f else 0f })
        show()
        compose.onNodeWithContentDescription("Bass, 63 Hz").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(CHART).performScrollTo().performTouchInput {
            down(Offset(width * 0.15f, height * 0.5f))
            moveBy(Offset(0f, -height * 0.25f), delayMillis = 200)
            up()
        }
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertEquals(3f, state.value.bandGainsDb[1])
        }
    }

    @Test fun resetRefreshesAllTenDisplayedLevelsWithoutEnablingAnOffEq() {
        state.value = EqualizerSettings(false, List(10) { 5f })
        show()
        compose.onNodeWithText("Reset to flat").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Air / high treble, 16 kHz, +0 dB").assertExists()
        compose.runOnIdle {
            assertFalse(state.value.enabled)
            assertEquals(List(10) { 0f }, state.value.bandGainsDb)
        }
    }

    private companion object {
        const val CHART = "Music level bars behind equalizer settings, from bass to treble. The line and numbers show EQ gain in decibels."
    }
}
