package com.carlink.app.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import com.carlink.app.ui.wired.WiredHomeContent
import com.carlink.app.ui.wired.WiredSettingsContent
import com.carlink.app.ui.wired.EqualizerContent
import com.carlink.app.ui.wired.CallAudioContent
import com.carlink.core.audio.EqualizerSettings
import com.carlink.core.settings.SystemBarVisibility
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport

class GalleryEntry(val name: String, val content: @Composable () -> Unit)
val galleryEntries get() = f150Entries()

fun f150Entries() = listOf(
    GalleryEntry("ready") { WiredHomeContent(LinkState.Idle) },
    GalleryEntry("permissions") { WiredHomeContent(LinkState.Idle, needsPermissions = true) },
    GalleryEntry("connecting") {
        WiredHomeContent(LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB))
    },
    GalleryEntry("auth-missing") {
        WiredHomeContent(LinkState(LinkStage.FAILED, Engine.CARPLAY, Transport.USB, problem = LinkProblem.CARPLAY_AUTH_MISSING))
    },
    GalleryEntry("settings") { WiredSettingsContent(180) },
    GalleryEntry("settings-bars") { WiredSettingsContent(180, bars = SystemBarVisibility(true, true)) },
    GalleryEntry("equalizer") { EqualizerContent(EqualizerSettings(true, listOf(3f, 1f, 0f, -2f, 2f))) },
    GalleryEntry("equalizer-off") { EqualizerContent(EqualizerSettings()) },
    GalleryEntry("call-audio") { CallAudioContent(true) },
    GalleryEntry("call-audio-off") { CallAudioContent(false) },
    GalleryEntry("connected") {
        WiredHomeContent(LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.USB))
    },
    GalleryEntry("about") { com.carlink.app.ui.about.AboutScreen(onBack = {}) },
    GalleryEntry("native-waiting") {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = com.carlink.app.ui.home.CarPlayWaitingContent::create, modifier = Modifier.fillMaxSize())
    },
)
