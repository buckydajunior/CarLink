package com.carlink.app.ui.wired

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.F150Profile
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.Route
import com.carlink.app.ui.home.IdleClockView
import com.carlink.app.ui.home.IdleConnectionUi
import com.carlink.app.ui.home.IdleAction
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkState
import com.carlink.core.settings.SystemBarVisibility

/** The same clock surface is used by Home and the engine during USB authentication. */
@Composable
fun WiredHomeScreen(vm: CarLinkViewModel, navigate: (Route) -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val needsPermissions by vm.permissionsNeeded.collectAsStateWithLifecycle()
    val fix = rememberProblemFix(vm)
    WiredHomeContent(
        link = home.link,
        needsPermissions = needsPermissions,
        usbAttached = home.usbAttached,
        onSettings = { navigate(Route.SETTINGS) },
        onConnect = vm::connectUsb,
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
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B111A)) {
        AndroidView(
            factory = { IdleClockView(it) },
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            update = { view ->
                view.bind(IdleConnectionUi.from(link, needsPermissions, usbAttached), onSettings) { action ->
                    when (action) {
                        IdleAction.ALLOW -> onFix()
                        IdleAction.CONNECT -> onConnect()
                        IdleAction.RETRY -> onRetry()
                        IdleAction.OPEN -> onOpen()
                        IdleAction.DISCONNECT -> onDisconnect()
                    }
                }
            },
        )
    }
}

@Composable
fun WiredSettingsScreen(vm: CarLinkViewModel, onBack: () -> Unit, onAbout: () -> Unit, onEqualizer: () -> Unit,
    onCallAudio: () -> Unit) {
    val width by vm.carPlayWidth.collectAsStateWithLifecycle()
    val bars by vm.systemBars.collectAsStateWithLifecycle()
    val equalizer by vm.equalizer.collectAsStateWithLifecycle()
    WiredSettingsContent(width, vm::setCarPlayWidth, onBack, onAbout, vm::shareWiredLog,
        bars, vm::showStatusBar, vm::showBottomBar, equalizer.enabled, onEqualizer, onCallAudio)
}

@Composable
fun WiredSettingsContent(
    width: Int,
    onSize: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onAbout: () -> Unit = {},
    onShareLog: () -> Unit = {},
    bars: SystemBarVisibility = SystemBarVisibility(),
    onStatusBar: (Boolean) -> Unit = {},
    onBottomBar: (Boolean) -> Unit = {},
    equalizerEnabled: Boolean = false,
    onEqualizer: () -> Unit = {},
    onCallAudio: () -> Unit = {},
) {
    val labels = listOf(
        R.string.carlink_f150_size_standard, R.string.carlink_f150_size_large,
        R.string.carlink_f150_size_xl, R.string.carlink_f150_size_xxl, R.string.carlink_f150_size_max,
    )
    WiredPage {
        WiredBackButton(onBack)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.carlink_f150_tablet_bars), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_bars_body), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                PreferenceSwitch(stringResource(R.string.carlink_f150_status_bar), bars.statusBar, onStatusBar)
                HorizontalDivider()
                PreferenceSwitch(stringResource(R.string.carlink_f150_bottom_bar), bars.bottomBar, onBottomBar)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onEqualizer, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_equalizer), Modifier.weight(1f))
            Text(stringResource(if (equalizerEnabled) R.string.carlink_f150_on else R.string.carlink_f150_off))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onCallAudio, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_call_audio))
        }
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.carlink_f150_icon_size), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_size_body), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        F150Profile.carPlayWidths.toList().chunked(2).forEach { widths ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                widths.forEach { value ->
                    val label = stringResource(labels[F150Profile.carPlayWidths.indexOf(value)])
                    val modifier = Modifier.weight(1f).heightIn(min = CarLinkTokens.dimens.minTouch)
                    if (width == value) Button({ onSize(value) }, modifier) { Text(label) }
                    else OutlinedButton({ onSize(value) }, modifier) { Text(label) }
                }
                if (widths.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(16.dp))
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
internal fun PreferenceSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun WiredBackButton(onBack: () -> Unit) {
    OutlinedButton(onBack, Modifier.heightIn(min = CarLinkTokens.dimens.minTouch)) {
        Icon(painterResource(R.drawable.carlink_ic_arrow_back), null, Modifier.size(28.dp))
        Text(stringResource(R.string.carlink_f150_back), Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun WiredPage(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(24.dp), content = content)
    }
}
