package com.carlink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.ui.about.AboutScreen
import com.carlink.app.ui.wired.WiredHomeScreen
import com.carlink.app.ui.wired.WiredSettingsScreen

enum class Route { HOME, SETTINGS, ABOUT }

/** Three screens, with the CarPlay car tile returning to its running projection on Back. */
@Composable
fun CarLinkApp(vm: CarLinkViewModel, onExitToCarHome: () -> Unit) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var fromCar by rememberSaveable { mutableStateOf(false) }
    val requests by vm.settingsRequests.collectAsStateWithLifecycle()
    LaunchedEffect(requests) { if (requests > 0) { route = Route.SETTINGS; fromCar = true } }
    val back: () -> Unit = {
        when (route) {
            Route.ABOUT -> route = Route.SETTINGS
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
        Route.SETTINGS -> WiredSettingsScreen(vm, onBack = back, onAbout = { route = Route.ABOUT })
        Route.ABOUT -> AboutScreen(onBack = back)
    }
}
