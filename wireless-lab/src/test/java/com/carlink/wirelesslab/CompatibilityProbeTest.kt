package com.carlink.wirelesslab

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CompatibilityProbeTest {
    private open class Radios : RadioAccess {
        var countReads = 0
        override fun deviceInfo() = mapOf(Check.MODEL to "rk3399")
        override fun bluetoothFeature() = true
        override fun bluetoothLeFeature() = false
        override fun bluetoothPermission() = true
        override fun bluetoothAdapterPresent() = true
        override fun bluetoothEnabled() = true
        override fun bondedCount(): Int { countReads++; return 1 }
        override fun wifiFeature() = true
        override fun wifiServicePresent() = true
        override fun wifiEnabled() = true
        override fun wifi5Ghz() = true
        override fun wifiDirectFeature() = true
        override fun wifiDirectServicePresent() = true
    }

    @Test fun permissionDenialKeepsWifiResultsWithoutReadingPairedDevices() {
        val access = object : Radios() { override fun bluetoothPermission() = false }
        val result = CompatibilityProbe(access).collect().readings
        assertEquals(Reading.PermissionDenied, result[Check.BLUETOOTH_ENABLED])
        assertEquals(Reading.PermissionDenied, result[Check.BONDED_COUNT])
        assertEquals(0, access.countReads)
        assertEquals(Reading.Flag(true), result[Check.WIFI_5_GHZ])
    }

    @Test fun firmwareFeatureDoesNotOverrideMissingAdapter() {
        val access = object : Radios() { override fun bluetoothAdapterPresent() = false }
        val result = CompatibilityProbe(access).collect().readings
        assertEquals(Reading.Flag(true), result[Check.BLUETOOTH_FEATURE])
        assertEquals(Reading.Flag(false), result[Check.BLUETOOTH_ADAPTER])
        assertEquals(Reading.Unavailable, result[Check.BLUETOOTH_ENABLED])
        assertEquals(Reading.Unavailable, result[Check.BONDED_COUNT])
        assertEquals(0, access.countReads)
    }

    @Test fun offRadioDoesNotReportMisleadingZeroPairedDevices() {
        val access = object : Radios() { override fun bluetoothEnabled() = false }
        val result = CompatibilityProbe(access).collect().readings
        assertEquals(Reading.Flag(false), result[Check.BLUETOOTH_ENABLED])
        assertEquals(Reading.NotChecked, result[Check.BONDED_COUNT])
        assertEquals(0, access.countReads)
    }

    @Test fun oemFailuresStayDistinctFromMissingHardwareAndDoNotLeakMessages() {
        val secret = "phone-name 01:23:45:67:89:AB secret-network"
        val access = object : Radios() {
            override fun bluetoothAdapterPresent(): Boolean = throw IllegalStateException(secret)
            override fun wifiEnabled(): Boolean = throw SecurityException(secret)
        }
        val report = CompatibilityProbe(access).collect()
        assertEquals(Reading.Error("IllegalStateException"), report.readings[Check.BLUETOOTH_ADAPTER])
        assertEquals(Reading.NotChecked, report.readings[Check.BLUETOOTH_ENABLED])
        assertEquals(Reading.PermissionDenied, report.readings[Check.WIFI_ENABLED])
        assertEquals(Reading.NotChecked, report.readings[Check.WIFI_5_GHZ])
        val formatted = report.format(RuntimeEnvironment.getApplication())
        assertFalse(formatted.contains(secret))
        assertFalse(formatted.contains("01:23:45:67:89:AB"))
    }

    @Test fun reportDoesNotClaimAPassingHandshakeFromAvailableRadios() {
        val report = CompatibilityProbe(Radios()).collect().format(RuntimeEnvironment.getApplication())
        assertTrue(report.contains("Not tested: Bluetooth socket connections"))
        assertTrue(report.contains("adapter presence do not prove wireless CarPlay works"))
        assertTrue(report.contains("does not prove a 5 GHz hotspot"))
        assertFalse(report.contains("Wireless CarPlay ready"))
    }

    @Test fun offWifiDoesNotTurnAnInactiveHalIntoNoFiveGhzSupport() {
        val result = CompatibilityProbe(object : Radios() {
            override fun wifiEnabled() = false
            override fun wifi5Ghz(): Boolean = error("Must not read inactive HAL")
        }).collect().readings
        assertEquals(Reading.NotChecked, result[Check.WIFI_5_GHZ])
    }

    @Test fun settingsComponentsDoNotOverrideMissingRadioOrProveWirelessSupport() {
        val report = CompatibilityProbe(object : Radios() {
            override fun bluetoothAdapterPresent() = false
            override fun bluetoothSettingsHandler() = "com.android.settings/.BluetoothSettings"
            override fun androidBluetoothPage() = "com.android.settings/.Settings\$BluetoothSettingsActivity"
        }).collect()
        assertEquals(Reading.Flag(false), report.readings[Check.BLUETOOTH_ADAPTER])
        assertEquals(Reading.Unavailable, report.readings[Check.BONDED_COUNT])
        val formatted = report.format(RuntimeEnvironment.getApplication())
        assertTrue(formatted.contains("Settings handlers may redirect internally"))
        assertTrue(formatted.contains("does not prove a working Bluetooth stack"))
    }

    @Test fun hiddenSettingsQueriesLeaveOtherRadioChecksIntact() {
        val result = CompatibilityProbe(Radios()).collect().readings
        assertEquals(Reading.Unavailable, result[Check.BLUETOOTH_SETTINGS_HANDLER])
        assertEquals(Reading.Unavailable, result[Check.ANDROID_BLUETOOTH_PAGE])
        assertEquals(Reading.Flag(true), result[Check.BLUETOOTH_ADAPTER])
        assertEquals(Reading.Text("1"), result[Check.BONDED_COUNT])
    }

    @Test fun missingWifiServiceIsUnavailableRatherThanAnOffRadio() {
        val result = CompatibilityProbe(object : Radios() {
            override fun wifiServicePresent() = false
        }).collect().readings
        assertEquals(Reading.Unavailable, result[Check.WIFI_ENABLED])
        assertEquals(Reading.Unavailable, result[Check.WIFI_5_GHZ])
        assertEquals(Reading.Flag(true), result[Check.WIFI_FEATURE])
    }
}
