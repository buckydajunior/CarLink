package com.carlink.app.ui.wired

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.F150Profile
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.Route
import com.carlink.app.ui.home.rememberProblemFix
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.PhoneKind

/** The USB-only product surface: no pairing, network picker, phone list or setup wizard. */
@Composable
fun WiredHomeScreen(vm: CarLinkViewModel, navigate: (Route) -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val needsPermissions by vm.permissionsNeeded.collectAsStateWithLifecycle()
    val fix = rememberProblemFix(vm)
    WiredHomeContent(
        link = home.link,
        needsPermissions = needsPermissions,
        usbAttached = PhoneKind.IPHONE in home.usbKinds,
        onSettings = { navigate(Route.SETTINGS) },
        onConnect = { vm.connectUsb(PhoneKind.IPHONE) },
        onRetry = vm::retry,
        onDisconnect = vm::disconnect,
        onOpen = vm::returnToProjection,
        onFix = { fix(if (needsPermissions) LinkProblem.PERMISSIONS else home.link.problem ?: LinkProblem.PERMISSIONS) },
    )
}

@Composable
fun WiredHomeContent(
    link: LinkState,
    needsPermissions: Boolean = false,
    usbAttached: Boolean = false,
    onSettings: () -> Unit = {},
    onConnect: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onOpen: () -> Unit = {},
    onFix: () -> Unit = {},
) {
    val connecting = link.stage == LinkStage.CONNECTING || link.stage == LinkStage.WAITING
    val title = when {
        needsPermissions -> R.string.carlink_f150_access_title
        link.stage == LinkStage.CONNECTED -> R.string.carlink_f150_connected
        connecting -> R.string.carlink_f150_connecting
        link.stage == LinkStage.FAILED -> R.string.carlink_f150_failed
        else -> R.string.carlink_f150_ready
    }
    val body = when {
        needsPermissions || link.problem == LinkProblem.PERMISSIONS -> R.string.carlink_f150_access_body
        link.problem == LinkProblem.CARPLAY_AUTH_MISSING -> R.string.carlink_f150_auth_missing
        link.problem == LinkProblem.VPN_CONSENT -> R.string.carlink_home_error_vpn_body
        link.stage == LinkStage.CONNECTED -> R.string.carlink_f150_connected_body
        connecting -> R.string.carlink_f150_connecting_body
        link.stage == LinkStage.FAILED -> R.string.carlink_f150_failed_body
        else -> R.string.carlink_f150_ready_body
    }
    val fixable = needsPermissions || link.problem == LinkProblem.PERMISSIONS || link.problem == LinkProblem.VPN_CONSENT
    WiredPage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.carlink_f150_vehicle), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onSettings, Modifier.heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Icon(painterResource(R.drawable.carlink_ic_settings), null, Modifier.size(32.dp))
                Text(stringResource(R.string.carlink_f150_settings), Modifier.padding(start = 12.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(painterResource(R.drawable.carlink_ic_usb), null, Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
                when {
                    fixable -> WiredButton(stringResource(R.string.carlink_f150_allow), onFix)
                    link.stage == LinkStage.CONNECTED -> WiredButton(stringResource(R.string.carlink_f150_open), onOpen)
                    link.stage == LinkStage.FAILED && link.problem != LinkProblem.CARPLAY_AUTH_MISSING ->
                        WiredButton(stringResource(R.string.carlink_f150_retry), onRetry)
                    !connecting && usbAttached -> WiredButton(stringResource(R.string.carlink_f150_connect), onConnect)
                }
                if (connecting || link.stage == LinkStage.CONNECTED) {
                    OutlinedButton(onDisconnect, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                        Text(stringResource(R.string.carlink_f150_disconnect))
                    }
                }
            }
        }
    }
}

@Composable
fun WiredSettingsScreen(vm: CarLinkViewModel, onBack: () -> Unit, onAbout: () -> Unit) {
    val width by vm.carPlayWidth.collectAsStateWithLifecycle()
    WiredSettingsContent(width, vm::setCarPlayWidth, onBack, onAbout, vm::shareWiredLog)
}

@Composable
fun WiredSettingsContent(width: Int, onSize: (Int) -> Unit = {}, onBack: () -> Unit = {},
    onAbout: () -> Unit = {}, onShareLog: () -> Unit = {}) {
    val labels = listOf(
        R.string.carlink_f150_size_standard, R.string.carlink_f150_size_large,
        R.string.carlink_f150_size_xl, R.string.carlink_f150_size_xxl, R.string.carlink_f150_size_max,
    )
    WiredPage {
        OutlinedButton(onBack, Modifier.heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Icon(painterResource(R.drawable.carlink_ic_arrow_back), null, Modifier.size(32.dp))
            Text(stringResource(R.string.carlink_f150_back), Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.carlink_f150_icon_size), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_size_body), Modifier.padding(vertical = 20.dp),
            style = MaterialTheme.typography.bodyLarge)
        F150Profile.carPlayWidths.forEachIndexed { index, value ->
            val label = stringResource(labels[index])
            if (width == value) {
                WiredButton(label, { onSize(value) })
            } else {
                OutlinedButton({ onSize(value) }, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                    Text(label)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onShareLog, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_share_log))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onAbout, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_about))
        }
    }
}

@Composable
private fun WiredPage(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(24.dp), content = content)
    }
}

@Composable
private fun WiredButton(label: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) { Text(label) }
}
