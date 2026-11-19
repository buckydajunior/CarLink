package com.carlink.app.ui.wired

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.theme.CarLinkTokens

@Composable
fun CallAudioScreen(vm: CarLinkViewModel, onBack: () -> Unit) {
    val enabled by vm.callEchoCancellation.collectAsStateWithLifecycle()
    CallAudioContent(enabled, vm::setCallEchoCancellation, onBack, vm::shareWiredLog)
}

@Composable
fun CallAudioContent(
    echoCancellation: Boolean,
    onEchoCancellation: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    onShareLog: () -> Unit = {},
) {
    WiredPage {
        WiredBackButton(onBack)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.carlink_f150_call_audio), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_call_echo_body), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                PreferenceSwitch(stringResource(R.string.carlink_f150_call_echo), echoCancellation, onEchoCancellation)
            }
        }
        Text(stringResource(R.string.carlink_f150_call_echo_apply), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onShareLog, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_share_log))
        }
    }
}
