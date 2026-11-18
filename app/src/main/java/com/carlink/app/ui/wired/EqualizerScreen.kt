package com.carlink.app.ui.wired

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.audio.EqualizerController
import com.carlink.core.audio.EqualizerSettings
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(vm: CarLinkViewModel, onBack: () -> Unit) {
    val settings by vm.equalizer.collectAsStateWithLifecycle()
    EqualizerContent(settings, vm::setEqualizerEnabled, vm::setEqualizerBand, vm::resetEqualizer, onBack)
}

@Composable
fun EqualizerContent(
    settings: EqualizerSettings,
    onEnabled: (Boolean) -> Unit = {},
    onBand: (Int, Float) -> Unit = { _, _ -> },
    onReset: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    WiredPage {
        WiredBackButton(onBack)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.carlink_f150_equalizer), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_eq_body), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        PreferenceSwitch(stringResource(R.string.carlink_f150_eq_enable), settings.enabled, onEnabled)
        HorizontalDivider(Modifier.padding(bottom = 16.dp))
        EqualizerController.frequenciesHz.forEachIndexed { index, frequency ->
            EqualizerBand(frequency, settings.bandGainsDb[index], settings.enabled) { onBand(index, it) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onReset, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_eq_reset))
        }
        Text(stringResource(R.string.carlink_f150_eq_headroom), Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EqualizerBand(frequency: Int, gain: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    var position by remember(gain) { mutableFloatStateOf(gain) }
    val label = stringResource(if (frequency < 1000) R.string.carlink_f150_hz else R.string.carlink_f150_khz,
        if (frequency < 1000) frequency.toString() else (frequency / 1000f).toString().removeSuffix(".0"))
    val db = stringResource(R.string.carlink_f150_db, position.roundToInt())
    val control: @Composable (Modifier) -> Unit = { modifier ->
        Slider(
            value = position,
            onValueChange = { position = it.roundToInt().toFloat() },
            onValueChangeFinished = { onCommit(position) },
            enabled = enabled,
            valueRange = -12f..12f,
            steps = 23,
            modifier = modifier.heightIn(min = CarLinkTokens.dimens.minTouch)
                .semantics { contentDescription = label },
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, Modifier.width(132.dp), style = MaterialTheme.typography.titleSmall)
                control(Modifier.weight(1f))
                Text(db, Modifier.width(112.dp), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(db, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                control(Modifier.fillMaxWidth())
            }
        }
    }
}
