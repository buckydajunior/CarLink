package com.carlink.wirelesslab

import android.content.Context
import androidx.annotation.StringRes

/** No phone identity, address, SSID or pairing data is part of this report model. */
internal sealed interface Reading {
    data class Text(val value: String) : Reading
    data class Flag(val value: Boolean) : Reading
    data object PermissionDenied : Reading
    data object Unavailable : Reading
    data object NotChecked : Reading
    data class Error(val type: String) : Reading
}

internal enum class Check(@param:StringRes val label: Int) {
    ANDROID(R.string.carlink_lab_android),
    MODEL(R.string.carlink_lab_model),
    MANUFACTURER(R.string.carlink_lab_manufacturer),
    BOARD(R.string.carlink_lab_board),
    HARDWARE(R.string.carlink_lab_hardware),
    BUILD(R.string.carlink_lab_build),
    ABI(R.string.carlink_lab_abi),
    BLUETOOTH_FEATURE(R.string.carlink_lab_bt_feature),
    BLUETOOTH_LE_FEATURE(R.string.carlink_lab_ble_feature),
    BLUETOOTH_PERMISSION(R.string.carlink_lab_bt_permission),
    BLUETOOTH_ADAPTER(R.string.carlink_lab_bt_adapter),
    BLUETOOTH_ENABLED(R.string.carlink_lab_bt_enabled),
    BONDED_COUNT(R.string.carlink_lab_bond_count),
    BLUETOOTH_SETTINGS_HANDLER(R.string.carlink_lab_bt_settings_handler),
    ANDROID_BLUETOOTH_PAGE(R.string.carlink_lab_android_bt_page),
    WIFI_FEATURE(R.string.carlink_lab_wifi_feature),
    WIFI_SERVICE(R.string.carlink_lab_wifi_service),
    WIFI_ENABLED(R.string.carlink_lab_wifi_enabled),
    WIFI_5_GHZ(R.string.carlink_lab_wifi_5ghz),
    WIFI_DIRECT_FEATURE(R.string.carlink_lab_p2p_feature),
    WIFI_DIRECT_SERVICE(R.string.carlink_lab_p2p_service),
    OEM_HOME(R.string.carlink_lab_oem_home),
    OEM_APPS(R.string.carlink_lab_oem_apps),
    OEM_SERIAL_NODE(R.string.carlink_lab_oem_serial),
}

internal data class CompatibilityReport(val readings: Map<Check, Reading>) {
    fun format(context: Context): String = buildString {
        appendLine(context.getString(R.string.carlink_lab_report_title, BuildConfig.VERSION_NAME))
        appendLine(context.getString(R.string.carlink_lab_report_scope))
        appendLine()
        for (check in Check.entries) {
            append(context.getString(check.label)).append(": ")
            appendLine((readings[check] ?: Reading.NotChecked).format(context))
        }
        appendLine()
        appendLine(context.getString(R.string.carlink_lab_limits))
        appendLine()
        appendLine(context.getString(R.string.carlink_lab_privacy))
    }
}

private fun Reading.format(context: Context): String = when (this) {
    is Reading.Text -> value
    is Reading.Flag -> context.getString(if (value) R.string.carlink_lab_yes else R.string.carlink_lab_no)
    Reading.PermissionDenied -> context.getString(R.string.carlink_lab_denied)
    Reading.Unavailable -> context.getString(R.string.carlink_lab_unavailable)
    Reading.NotChecked -> context.getString(R.string.carlink_lab_not_checked)
    is Reading.Error -> context.getString(R.string.carlink_lab_error_type, type)
}
