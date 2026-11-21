package com.carlink.app.ui.wired

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.LocalUiProfile
import com.carlink.app.ui.theme.UiProfile
import com.carlink.core.audio.EqualizerController
import com.carlink.core.audio.EqualizerSettings
import com.carlink.core.audio.MusicSpectrumController
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(vm: CarLinkViewModel, onBack: () -> Unit) {
    val settings by vm.equalizer.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observation = observeMusicWhileStarted(lifecycle, MusicSpectrumController::observe)
        onDispose { observation.close() }
    }
    val spectrum = MusicSpectrumController.spectrum.collectAsStateWithLifecycle()
    // The draw scope reads the changing levels. Music does not recompose the whole settings page.
    val levels = remember(spectrum) { { spectrum.value.levelsDbFs } }
    val active = remember(spectrum) { { spectrum.value.hasAudio } }
    EqualizerContent(settings, vm::setEqualizerEnabled, vm::setEqualizerBand, vm::resetEqualizer, onBack,
        liveLevelsDbFs = levels, liveAudio = active)
}

@Composable
fun EqualizerContent(
    settings: EqualizerSettings,
    onEnabled: (Boolean) -> Unit = {},
    onBand: (Int, Float) -> Unit = { _, _ -> },
    onReset: () -> Unit = {},
    onBack: () -> Unit = {},
    liveLevelsDbFs: () -> List<Float> = { emptyList() },
    liveAudio: () -> Boolean = { false },
) {
    val frequencies = EqualizerController.frequenciesHz
    var selected by rememberSaveable { mutableIntStateOf(1) }
    var gains by remember(settings.bandGainsDb) {
        mutableStateOf(List(frequencies.size) { settings.bandGainsDb.getOrNull(it)?.coerceIn(-12f, 12f) ?: 0f })
    }
    val names = equalizerBandNames()
    val frequencyLabels = frequencies.map { frequencyLabel(it) }
    val car = LocalUiProfile.current == UiProfile.CAR
    val axisWidth = (if (car) 52.dp else 28.dp) * LocalDensity.current.fontScale
    val numberStyle = if (car) MaterialTheme.typography.labelMedium
        else MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
    fun preview(index: Int, gain: Float) {
        gains = gains.mapIndexed { band, old -> if (band == index) gain.roundToInt().toFloat().coerceIn(-12f, 12f) else old }
    }
    WiredPage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            WiredBackButton(onBack)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (settings.enabled) R.string.carlink_f150_on else R.string.carlink_f150_off),
                    style = MaterialTheme.typography.titleSmall)
                Switch(settings.enabled, onEnabled,
                    Modifier.sizeIn(minWidth = CarLinkTokens.dimens.minTouch, minHeight = CarLinkTokens.dimens.minTouch)
                        .semantics { contentDescription = names.enable })
            }
        }
        Text(stringResource(R.string.carlink_f150_equalizer), Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.carlink_f150_eq_ten_bands), Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.carlink_f150_eq_bass), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.carlink_f150_eq_mids), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.carlink_f150_eq_treble), style = MaterialTheme.typography.labelMedium)
                }
                SpectrumLegend(liveAudio)
                Spacer(Modifier.height(16.dp))
                EqualizerCurve(gains, selected, settings.enabled, liveLevelsDbFs,
                    onSelect = { selected = it },
                    onPreview = { band, gain -> selected = band; preview(band, gain) },
                    onCommit = { band -> onBand(band, gains[band]) })
                Row(Modifier.fillMaxWidth().padding(start = axisWidth)) {
                    frequencies.indices.forEach { index ->
                        val db = stringResource(R.string.carlink_f150_db, gains[index].roundToInt())
                        val description = stringResource(R.string.carlink_f150_eq_band_description,
                            names.bands[index], frequencyLabels[index], db)
                        Column(Modifier.weight(1f).heightIn(min = CarLinkTokens.dimens.minTouch)
                            .selectable(selected == index, role = Role.Tab, onClick = { selected = index })
                            .semantics { contentDescription = description },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text(compactFrequency(frequencies[index]), style = numberStyle, maxLines = 1, softWrap = false,
                                color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.carlink_f150_eq_short_db, gains[index].roundToInt()),
                                style = numberStyle, maxLines = 1, softWrap = false,
                                color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.carlink_f150_eq_curve_hint), Modifier.padding(top = 12.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names.bands[selected], style = MaterialTheme.typography.titleLarge)
                        Text(frequencyLabels[selected], style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.carlink_f150_db, gains[selected].roundToInt()),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                // A selected band owns its gesture. Switching bands disposes the old slider,
                // so an in-flight pointer cannot retarget its gain or release to another band.
                key(selected) {
                    val band = selected
                    Slider(gains[band], { preview(band, it) },
                        onValueChangeFinished = { onBand(band, gains[band]) },
                        enabled = settings.enabled, valueRange = -12f..12f, steps = 23,
                        modifier = Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)
                            .semantics { contentDescription = names.bands[band] + ", " + frequencyLabels[band] })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ selected-- }, Modifier.weight(1f).heightIn(min = CarLinkTokens.dimens.minTouch),
                        enabled = selected > 0) { Text(stringResource(R.string.carlink_f150_eq_previous)) }
                    OutlinedButton({ selected++ }, Modifier.weight(1f).heightIn(min = CarLinkTokens.dimens.minTouch),
                        enabled = selected < frequencies.lastIndex) { Text(stringResource(R.string.carlink_f150_eq_next)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onReset, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
            Text(stringResource(R.string.carlink_f150_eq_reset))
        }
        Text(stringResource(R.string.carlink_f150_eq_body), Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.carlink_f150_eq_headroom), Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EqualizerCurve(
    gains: List<Float>, selected: Int, enabled: Boolean, liveLevelsDbFs: () -> List<Float>,
    onSelect: (Int) -> Unit, onPreview: (Int, Float) -> Unit, onCommit: (Int) -> Unit,
) {
    val currentGains by rememberUpdatedState(gains)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val select by rememberUpdatedState(onSelect)
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val musicColor = MaterialTheme.colorScheme.tertiary
    val axisWidth = (if (LocalUiProfile.current == UiProfile.CAR) 52.dp else 28.dp) * LocalDensity.current.fontScale
    val description = stringResource(R.string.carlink_f150_eq_curve_description)
    Row(Modifier.fillMaxWidth().height(184.dp)) {
        Column(Modifier.width(axisWidth).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.carlink_f150_eq_short_db, 12), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            Text(stringResource(R.string.carlink_f150_eq_short_db, 0), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            Text(stringResource(R.string.carlink_f150_eq_short_db, -12), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }
            .pointerInput(enabled) {
                detectTapGestures { point -> select((point.x / size.width * currentGains.size).toInt().coerceIn(currentGains.indices)) }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    var band = 0
                    var startGain = 0f
                    var dragGain = 0f
                    detectDragGestures(
                        onDragStart = { point ->
                            band = (point.x / size.width * currentGains.size).toInt().coerceIn(currentGains.indices)
                            startGain = currentGains[band]
                            dragGain = startGain
                            select(band)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragGain = (dragGain - amount.y * 24f / (size.height - 24.dp.toPx())).coerceIn(-12f, 12f)
                            preview(band, dragGain)
                        },
                        onDragEnd = { commit(band) },
                        onDragCancel = { preview(band, startGain) },
                    )
                }
            }) {
            val top = 12.dp.toPx()
            val plotHeight = size.height - top * 2
            val step = size.width / gains.size
            fun x(index: Int) = step * (index + 0.5f)
            fun y(gain: Float) = top + (12f - gain) / 24f * plotHeight
            for (gain in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(grid, Offset(0f, y(gain)), Offset(size.width, y(gain)),
                    strokeWidth = if (gain == 0f) 2.dp.toPx() else 1.dp.toPx())
            }
            gains.indices.forEach { index ->
                drawLine(grid.copy(alpha = 0.45f), Offset(x(index), top), Offset(x(index), size.height - top))
            }
            // Bars use their own relative music-level scale (-60..0dBFS), independent of EQ gain.
            // Reading snapshot state here invalidates drawing rather than every page composable.
            val levels = liveLevelsDbFs()
            gains.indices.forEach { index ->
                val level = levels.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(-60f, 0f) ?: -60f
                val height = (level + 60f) / 60f * plotHeight
                if (height > 0f) drawLine(musicColor.copy(alpha = 0.38f),
                    Offset(x(index), size.height - top), Offset(x(index), size.height - top - height),
                    strokeWidth = step * 0.58f, cap = StrokeCap.Butt)
            }
            val curve = Path().apply {
                moveTo(x(0), y(gains[0]))
                for (index in 1 until gains.size) {
                    val middle = (x(index - 1) + x(index)) / 2
                    cubicTo(middle, y(gains[index - 1]), middle, y(gains[index]), x(index), y(gains[index]))
                }
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(x(gains.lastIndex), y(0f))
                lineTo(x(0), y(0f))
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.03f))))
            drawPath(curve, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            gains.forEachIndexed { index, gain ->
                val center = Offset(x(index), y(gain))
                if (index == selected) drawCircle(accent.copy(alpha = 0.16f), 15.dp.toPx(), center)
                drawCircle(surface, if (index == selected) 7.dp.toPx() else 5.dp.toPx(), center)
                drawCircle(accent, if (index == selected) 7.dp.toPx() else 5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SpectrumLegend(liveAudio: () -> Boolean) {
    val active by remember(liveAudio) { derivedStateOf(liveAudio) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.carlink_f150_eq_gain_legend), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (active) R.string.carlink_f150_eq_live_legend else R.string.carlink_f150_eq_live_waiting),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

private data class BandNames(val enable: String, val bands: List<String>)

@Composable
private fun equalizerBandNames() = BandNames(stringResource(R.string.carlink_f150_eq_enable), listOf(
    stringResource(R.string.carlink_f150_eq_sub_bass), stringResource(R.string.carlink_f150_eq_bass),
    stringResource(R.string.carlink_f150_eq_upper_bass), stringResource(R.string.carlink_f150_eq_low_mids),
    stringResource(R.string.carlink_f150_eq_mids), stringResource(R.string.carlink_f150_eq_vocals),
    stringResource(R.string.carlink_f150_eq_upper_mids), stringResource(R.string.carlink_f150_eq_highs),
    stringResource(R.string.carlink_f150_eq_treble), stringResource(R.string.carlink_f150_eq_air),
))

@Composable
private fun frequencyLabel(frequency: Int) = stringResource(
    if (frequency < 1000) R.string.carlink_f150_hz else R.string.carlink_f150_khz,
    compactFrequency(frequency).removeSuffix("k"),
)

private fun compactFrequency(frequency: Int) = if (frequency < 1000) frequency.toString() else "${frequency / 1000}k"
