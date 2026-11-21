package com.carlink.wirelesslab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build

/** Passive reads only. A visible adapter is not evidence that RFCOMM will work. */
internal interface RadioAccess {
    fun deviceInfo(): Map<Check, String>
    fun bluetoothFeature(): Boolean
    fun bluetoothLeFeature(): Boolean
    fun bluetoothPermission(): Boolean
    fun bluetoothAdapterPresent(): Boolean
    fun bluetoothEnabled(): Boolean
    fun bondedCount(): Int
    fun wifiFeature(): Boolean
    fun wifiServicePresent(): Boolean
    fun wifiEnabled(): Boolean
    fun wifi5Ghz(): Boolean
    fun wifiDirectFeature(): Boolean
    fun wifiDirectServicePresent(): Boolean
}

@SuppressLint("MissingPermission") // Probe preserves permission denial; on API31+ the Activity requests CONNECT first.
internal class AndroidRadioAccess(context: Context) : RadioAccess {
    private val app = context.applicationContext
    private val manager = app.packageManager
    // Initialized only on the worker, not while the screen is opening.
    private val bluetooth by lazy { app.getSystemService(BluetoothManager::class.java)?.adapter }
    private val wifi by lazy { app.getSystemService(WifiManager::class.java) }

    override fun deviceInfo() = linkedMapOf(
        Check.ANDROID to "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
        Check.MODEL to Build.MODEL,
        Check.MANUFACTURER to Build.MANUFACTURER,
        Check.BOARD to Build.BOARD,
        Check.HARDWARE to Build.HARDWARE,
        Check.BUILD to Build.DISPLAY,
        Check.ABI to Build.SUPPORTED_ABIS.joinToString(", "),
    )
    override fun bluetoothFeature() = manager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    override fun bluetoothLeFeature() = manager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    override fun bluetoothPermission(): Boolean = app.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH,
    ) == PackageManager.PERMISSION_GRANTED
    override fun bluetoothAdapterPresent() = bluetooth != null
    override fun bluetoothEnabled() = requireNotNull(bluetooth).isEnabled
    // Count only: never read a BluetoothDevice's name, address or UUIDs.
    override fun bondedCount() = requireNotNull(bluetooth).bondedDevices.size
    override fun wifiFeature() = manager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    override fun wifiServicePresent() = wifi != null
    override fun wifiEnabled() = requireNotNull(wifi).isWifiEnabled
    override fun wifi5Ghz() = requireNotNull(wifi).is5GHzBandSupported
    override fun wifiDirectFeature() = manager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    override fun wifiDirectServicePresent() = app.getSystemService(Context.WIFI_P2P_SERVICE) != null
}

internal class CompatibilityProbe(private val radios: RadioAccess) {
    fun collect(): CompatibilityReport {
        val values = linkedMapOf<Check, Reading>()
        radios.deviceInfo().forEach { (check, value) ->
            values[check] = if (value.isBlank()) Reading.Unavailable else Reading.Text(value)
        }
        fun flag(check: Check, read: () -> Boolean) {
            values[check] = capture { Reading.Flag(read()) }
        }
        flag(Check.BLUETOOTH_FEATURE, radios::bluetoothFeature)
        flag(Check.BLUETOOTH_LE_FEATURE, radios::bluetoothLeFeature)
        flag(Check.BLUETOOTH_PERMISSION, radios::bluetoothPermission)
        // Adapter visibility is a separate observation from the firmware feature flag.
        flag(Check.BLUETOOTH_ADAPTER, radios::bluetoothAdapterPresent)
        val bluetoothDependency = when {
            values[Check.BLUETOOTH_PERMISSION] == Reading.Flag(false) -> Reading.PermissionDenied
            values[Check.BLUETOOTH_PERMISSION] != Reading.Flag(true) -> Reading.NotChecked
            values[Check.BLUETOOTH_ADAPTER] == Reading.Flag(false) -> Reading.Unavailable
            values[Check.BLUETOOTH_ADAPTER] != Reading.Flag(true) -> Reading.NotChecked
            else -> null
        }
        values[Check.BLUETOOTH_ENABLED] = bluetoothDependency ?: capture { Reading.Flag(radios.bluetoothEnabled()) }
        values[Check.BONDED_COUNT] = when {
            bluetoothDependency != null -> bluetoothDependency
            values[Check.BLUETOOTH_ENABLED] == Reading.Flag(false) -> Reading.NotChecked
            values[Check.BLUETOOTH_ENABLED] != Reading.Flag(true) -> Reading.NotChecked
            else -> capture { Reading.Text(radios.bondedCount().toString()) }
        }
        flag(Check.WIFI_FEATURE, radios::wifiFeature)
        flag(Check.WIFI_SERVICE, radios::wifiServicePresent)
        val wifiDependency = when (values[Check.WIFI_SERVICE]) {
            Reading.Flag(true) -> null
            Reading.Flag(false) -> Reading.Unavailable
            else -> Reading.NotChecked
        }
        values[Check.WIFI_ENABLED] = wifiDependency ?: capture { Reading.Flag(radios.wifiEnabled()) }
        values[Check.WIFI_5_GHZ] = when {
            wifiDependency != null -> wifiDependency
            // Android 8.1 can report no capabilities while its Wi-Fi HAL is stopped.
            values[Check.WIFI_ENABLED] != Reading.Flag(true) -> Reading.NotChecked
            else -> capture { Reading.Flag(radios.wifi5Ghz()) }
        }
        flag(Check.WIFI_DIRECT_FEATURE, radios::wifiDirectFeature)
        flag(Check.WIFI_DIRECT_SERVICE, radios::wifiDirectServicePresent)
        return CompatibilityReport(values)
    }

    private inline fun capture(read: () -> Reading): Reading = try {
        read()
    } catch (_: SecurityException) {
        Reading.PermissionDenied
    } catch (failure: Exception) {
        // OEM exception messages can contain device identities. Include only the exception class.
        Reading.Error(failure.javaClass.simpleName)
    }
}
