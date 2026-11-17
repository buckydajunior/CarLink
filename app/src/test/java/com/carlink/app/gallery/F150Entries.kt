package com.carlink.app.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.carlink.app.ui.wired.WiredHomeContent
import com.carlink.app.ui.wired.WiredSettingsContent
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport

val f150States = setOf("f150/ready", "f150/permissions", "f150/connecting", "f150/auth-missing", "f150/settings", "f150/native-waiting")

fun f150Entries() = listOf(
    GalleryEntry("f150", "ready") { WiredHomeContent(LinkState.Idle) },
    GalleryEntry("f150", "permissions") { WiredHomeContent(LinkState.Idle, needsPermissions = true) },
    GalleryEntry("f150", "connecting") {
        WiredHomeContent(LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB))
    },
    GalleryEntry("f150", "auth-missing") {
        WiredHomeContent(LinkState(LinkStage.FAILED, Engine.CARPLAY, Transport.USB, problem = LinkProblem.CARPLAY_AUTH_MISSING))
    },
    GalleryEntry("f150", "settings") { WiredSettingsContent(180) },
    GalleryEntry("f150", "native-waiting") {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = com.carlink.app.ui.home.CarPlayWaitingContent::create, modifier = Modifier.fillMaxSize())
    },
)
