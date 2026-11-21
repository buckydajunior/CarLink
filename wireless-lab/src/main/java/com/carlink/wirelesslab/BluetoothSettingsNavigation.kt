package com.carlink.wirelesslab

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Collections

internal data class SettingsPlan(
    val bluetoothPages: List<ComponentName> = emptyList(),
    val shortcutHandler: ComponentName? = null,
)

internal enum class SettingsDestination {
    ANDROID_BLUETOOTH_PAGE, BLUETOOTH_SHORTCUT, GENERAL_SETTINGS, UNAVAILABLE,
}

/** A resolved/launched component is a navigation observation, not proof of a working radio. */
internal data class SettingsLaunchResult(
    val destination: SettingsDestination,
    val component: ComponentName? = null,
)

internal object BluetoothSettingsNavigation {
    private val stockPages = listOf(
        ComponentName("com.android.settings", "com.android.settings.Settings\$BluetoothSettingsActivity"),
        ComponentName("com.android.settings", "com.android.settings.bluetooth.BluetoothSettings"),
    )

    /** Worker-thread package reads only. Never changes component, permission or radio state. */
    fun inspect(pm: PackageManager): SettingsPlan {
        val pages = stockPages.filter { component ->
            try {
                @Suppress("DEPRECATION")
                val info = pm.getActivityInfo(component, 0)
                info.exported && isEnabled(pm, component, info)
            } catch (_: Exception) {
                false // Missing, invisible or unreadable is not a usable explicit candidate.
            }
        }
        val shortcut = try {
            pm.resolveActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.let { info ->
                    if (info.packageName.isNullOrBlank() || info.name.isNullOrBlank()) null
                    else ComponentName(info.packageName, info.name)
                }
        } catch (_: Exception) {
            null
        }
        return SettingsPlan(Collections.unmodifiableList(pages), shortcut)
    }

    private fun isEnabled(pm: PackageManager, component: ComponentName, info: ActivityInfo): Boolean {
        val app = info.applicationInfo ?: return false
        val applicationEnabled = enabledState(pm.getApplicationEnabledSetting(component.packageName), app.enabled)
        val componentEnabled = enabledState(pm.getComponentEnabledSetting(component), info.enabled)
        return applicationEnabled && componentEnabled
    }

    private fun enabledState(state: Int, manifestEnabled: Boolean) = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    /** Explicit user action on the Activity thread. Resolution failures never suppress an intent. */
    fun launch(plan: SettingsPlan, startActivity: (Intent) -> Unit): SettingsLaunchResult {
        for (page in plan.bluetoothPages) {
            if (tryLaunch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setComponent(page), startActivity)) {
                return SettingsLaunchResult(SettingsDestination.ANDROID_BLUETOOTH_PAGE, page)
            }
        }
        if (tryLaunch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), startActivity)) {
            return SettingsLaunchResult(SettingsDestination.BLUETOOTH_SHORTCUT, plan.shortcutHandler)
        }
        if (tryLaunch(Intent(Settings.ACTION_SETTINGS), startActivity)) {
            return SettingsLaunchResult(SettingsDestination.GENERAL_SETTINGS)
        }
        return SettingsLaunchResult(SettingsDestination.UNAVAILABLE)
    }

    private fun tryLaunch(intent: Intent, startActivity: (Intent) -> Unit): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
