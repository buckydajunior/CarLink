package com.carlink.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.ui.about.AboutScreen
import com.carlink.app.ui.wired.WiredHomeScreen
import com.carlink.app.ui.wired.WiredSettingsScreen
import com.carlink.app.ui.wired.EqualizerScreen
import com.carlink.app.ui.wired.CallAudioScreen

enum class Route { HOME, SETTINGS, EQUALIZER, CALL_AUDIO, ABOUT }

/** The CarPlay car tile returns to its running projection on Back. */
@Composable
fun CarLinkApp(vm: CarLinkViewModel, onExitToCarHome: () -> Unit) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var fromCar by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val lightBars = route != Route.HOME && MaterialTheme.colorScheme.background.luminance() > .5f
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    val requests by vm.settingsRequests.collectAsStateWithLifecycle()
    LaunchedEffect(requests) { if (requests > 0) { route = Route.SETTINGS; fromCar = true } }
    val back: () -> Unit = {
        when (route) {
            Route.ABOUT, Route.EQUALIZER, Route.CALL_AUDIO -> route = Route.SETTINGS
            Route.SETTINGS -> {
                route = Route.HOME
                if (fromCar) vm.returnToProjection()
                fromCar = false
            }
            Route.HOME -> onExitToCarHome()
        }
    }
    BackHandler(onBack = back)
    when (route) {
        Route.HOME -> WiredHomeScreen(vm) { route = it }
        Route.SETTINGS -> WiredSettingsScreen(vm, onBack = back, onAbout = { route = Route.ABOUT },
            onEqualizer = { route = Route.EQUALIZER }, onCallAudio = { route = Route.CALL_AUDIO })
        Route.EQUALIZER -> EqualizerScreen(vm, onBack = back)
        Route.CALL_AUDIO -> CallAudioScreen(vm, onBack = back)
        Route.ABOUT -> AboutScreen(onBack = back)
    }
}
