package com.carlink.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlink.app.BuildConfig
import com.carlink.app.F150Profile
import com.carlink.app.R
import com.carlink.app.diagnostics.CarLinkFileProvider
import com.carlink.app.diagnostics.ConnectionLog
import com.carlink.app.entry.HomeLauncher
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.RenderingPolicy
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.connection.Connection
import com.carlink.core.connection.DevicePreflight
import com.carlink.core.connection.toLinkState
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.entry.UsbWatcher
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.UnifiedSettingsStore
import com.carlink.core.settings.SystemBarPreferences
import com.carlink.core.audio.EqualizerController
import com.carlink.core.audio.EqualizerSettings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DisplayPrefs(
    val themeMode: ThemeMode,
    val uiProfileMode: UiProfileMode,
    val interfaceScale: Float,
    val renderingMode: RenderingMode,
    val liteRendering: Boolean,
)

data class HomeState(val link: LinkState, val usbAttached: Boolean)

/** USB status and the small set of preferences exposed by this truck's receiver. */
class CarLinkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val session = Connection.get(app)
    private val uiPreferences = UiPreferences(app)
    private val store = UnifiedSettingsStore(app)
    private val bars = SystemBarPreferences(app)
    private val _usbAttached = MutableStateFlow(UsbWatcher.get(app).hasIphone())
    private val _permissionsNeeded = MutableStateFlow(missingPermissions().isNotEmpty())
    private val _carPlayWidth = MutableStateFlow(AirPlayPersistence.loadWidthPhysicalMm(app))
    private val _settingsRequests = MutableStateFlow(0L)
    private val _usbStartupPending = MutableStateFlow(false)
    private val _visibleRoute = MutableStateFlow<Route?>(null)
    private var awaitingCarSettingsFrame = false
    private val _systemBars = MutableStateFlow(bars.load())
    private val _equalizer = MutableStateFlow(EqualizerController.loadSettings(app))
    private val _callEchoCancellation = MutableStateFlow(AirPlayPersistence.loadCallEchoCancellation(app))

    val display: StateFlow<DisplayPrefs> = MutableStateFlow(DisplayPrefs(
        uiPreferences.themeMode, uiPreferences.uiProfileMode, uiPreferences.interfaceScale,
        uiPreferences.renderingMode, RenderingPolicy.lite(app, uiPreferences.renderingMode),
    ))
    val link: StateFlow<LinkState> = session.state.map { it.toLinkState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.toLinkState())
    val home: StateFlow<HomeState> = combine(link, _usbAttached, ::HomeState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeState(link.value, _usbAttached.value))
    val permissionsNeeded: StateFlow<Boolean> = _permissionsNeeded.asStateFlow()
    val carPlayWidth: StateFlow<Int> = _carPlayWidth.asStateFlow()
    val settingsRequests: StateFlow<Long> = _settingsRequests.asStateFlow()
    val systemBars = _systemBars.asStateFlow()
    val equalizer = _equalizer.asStateFlow()
    val callEchoCancellation = _callEchoCancellation.asStateFlow()
    /** Collected only while HomeActivity is RESUMED; route restoration must finish first. */
    internal val automaticProjectionReady = combine(link, _usbStartupPending, _visibleRoute) { state, pending, route ->
        pending && route == Route.HOME && state.stage == LinkStage.CONNECTED
    }
    internal val usbStartupPending: Boolean get() = _usbStartupPending.value
    internal val isHomeRouteVisible: Boolean get() = _visibleRoute.value == Route.HOME

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            _usbAttached.value = UsbWatcher.get(app).hasIphone()
        }
    }

    init {
        ContextCompat.registerReceiver(app, usbReceiver, android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() { app.unregisterReceiver(usbReceiver) }

    fun connectUsb() = session.connectUsb()
    fun retry() = session.retry()
    fun disconnect() { cancelAutomaticProjection(); session.disconnect() }
    fun openSettingsFromCar() {
        awaitingCarSettingsFrame = true
        onRouteShown(Route.SETTINGS)
        _settingsRequests.value++
    }

    internal fun requestUsbStartupProjection() {
        if (_visibleRoute.value == null || _visibleRoute.value == Route.HOME) _usbStartupPending.value = true
    }

    internal fun cancelAutomaticProjection() { _usbStartupPending.value = false }

    internal fun onRouteShown(route: Route) {
        // An old HOME SideEffect can precede the Settings navigation effect. Keep the
        // driver's requested route authoritative until Settings has actually rendered.
        if (awaitingCarSettingsFrame && route != Route.SETTINGS) return
        _visibleRoute.value = route
        if (route != Route.HOME) cancelAutomaticProjection()
    }

    internal fun onRouteRendered(route: Route) {
        if (route == Route.SETTINGS) awaitingCarSettingsFrame = false
        onRouteShown(route)
    }

    internal fun openStartupProjectionIfReady() {
        if (!_usbStartupPending.value || _visibleRoute.value != Route.HOME || link.value.stage != LinkStage.CONNECTED) return
        _usbStartupPending.value = false // Consume before launching: returning Home must not open it again.
        returnToProjection()
    }

    fun returnToProjection() {
        if (link.value.stage != LinkStage.CONNECTED) return
        cancelAutomaticProjection()
        HomeLauncher.returnToExistingProjection(app)
    }

    fun refresh() {
        _usbAttached.value = UsbWatcher.get(app).hasIphone()
        _permissionsNeeded.value = missingPermissions().isNotEmpty()
        _carPlayWidth.value = AirPlayPersistence.loadWidthPhysicalMm(app)
        _systemBars.value = bars.load()
        _equalizer.value = EqualizerController.loadSettings(app)
        _callEchoCancellation.value = AirPlayPersistence.loadCallEchoCancellation(app)
    }

    fun missingPermissions(): List<String> = DevicePreflight.requiredPermissions()
        .filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun recheckAfterFix() {
        refresh()
        if (link.value.stage == LinkStage.FAILED) retry()
    }

    /** The next session reads this value, so changing size never interrupts the current phone. */
    fun setCarPlayWidth(width: Int) {
        if (width !in F150Profile.carPlayWidths) return
        AirPlayPersistence.savePhysicalSizeBasis(app, AirPlayPhysicalSizeBasis.WIDTH)
        AirPlayPersistence.saveWidthPhysicalMm(app, width)
        _carPlayWidth.value = width
        store.save(store.load().copy(interfaceSize = InterfaceSize.CUSTOM))
    }

    fun showStatusBar(visible: Boolean) {
        bars.showStatusBar(visible)
        _systemBars.value = bars.load()
    }

    fun showBottomBar(visible: Boolean) {
        bars.showBottomBar(visible)
        _systemBars.value = bars.load()
    }

    fun setEqualizerEnabled(enabled: Boolean) = saveEqualizer(_equalizer.value.copy(enabled = enabled))

    fun setEqualizerBand(index: Int, gain: Float) {
        if (index !in EqualizerController.frequenciesHz.indices) return
        saveEqualizer(_equalizer.value.copy(bandGainsDb = _equalizer.value.bandGainsDb.mapIndexed { band, old ->
            if (band == index) gain else old
        }))
    }

    fun resetEqualizer() { _equalizer.value = EqualizerController.reset(app) }

    fun setCallEchoCancellation(enabled: Boolean) {
        AirPlayPersistence.saveCallEchoCancellation(app, enabled)
        _callEchoCancellation.value = enabled
    }

    private fun saveEqualizer(settings: EqualizerSettings) {
        _equalizer.value = EqualizerController.saveSettings(app, settings)
    }

    fun shareWiredLog() {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                ConnectionLog.export(app, "F-150 Lariat / Linkswell / USB CarPlay\n" +
                    "Version ${BuildConfig.VERSION_NAME}; Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "Device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; CarPlay width ${_carPlayWidth.value} mm\n" +
                    "Reported microphone: external, plugged into Linkswell\n" +
                    "Software call echo cancellation requested: ${_callEchoCancellation.value}")
            }
            val label = app.getString(R.string.carlink_f150_share_log)
            CarLinkFileProvider.shareText(app, file, label, label)
        }
    }
}
