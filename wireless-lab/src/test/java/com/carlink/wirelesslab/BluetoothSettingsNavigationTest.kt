package com.carlink.wirelesslab

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class BluetoothSettingsNavigationTest {
    private val pm get() = RuntimeEnvironment.getApplication().packageManager
    private val page = ComponentName("com.android.settings", "com.android.settings.Settings\$BluetoothSettingsActivity")
    private val alias = ComponentName("com.android.settings", "com.android.settings.bluetooth.BluetoothSettings")
    private val oem = ComponentName("com.example.headunit.bluetooth", "com.example.headunit.bluetooth.MainActivity")

    @Before fun installSettingsPackage() {
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = "com.android.settings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.android.settings"
                enabled = true
            }
        })
    }

    private fun add(component: ComponentName, exported: Boolean = true, enabled: Boolean = true) {
        val info = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            this.exported = exported
            this.enabled = enabled
            @Suppress("DEPRECATION")
            applicationInfo = pm.getApplicationInfo(component.packageName, 0)
        }
        shadowOf(pm).addOrUpdateActivity(info)
    }

    @Test fun inspectionFindsBothExportedEnabledStockPagesInOrder() {
        add(alias)
        add(page)
        val plan = BluetoothSettingsNavigation.inspect(pm)
        assertEquals(listOf(page, alias), plan.bluetoothPages)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(page))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getApplicationEnabledSetting(page.packageName))
    }

    @Test fun inspectionRejectsPrivateOrManifestDisabledActivities() {
        add(page, exported = false)
        add(alias, enabled = false)
        assertTrue(BluetoothSettingsNavigation.inspect(pm).bluetoothPages.isEmpty())
        @Suppress("DEPRECATION")
        assertFalse(pm.getActivityInfo(page, 0).exported)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(alias))
    }

    @Test fun inspectionHonorsUserDisabledComponentsAndDoesNotEnableThem() {
        add(page)
        add(alias)
        pm.setComponentEnabledSetting(page, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.DONT_KILL_APP)
        assertTrue(BluetoothSettingsNavigation.inspect(pm).bluetoothPages.isEmpty())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(page))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.getComponentEnabledSetting(alias))
    }

    @Test fun inspectionRejectsAnApplicationDisabledByTheUser() {
        add(page)
        add(alias)
        pm.setApplicationEnabledSetting("com.android.settings", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.DONT_KILL_APP)
        assertTrue(BluetoothSettingsNavigation.inspect(pm).bluetoothPages.isEmpty())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            pm.getApplicationEnabledSetting("com.android.settings"))
    }

    @Test fun inspectionRecordsAnOemShortcutWithoutCallingItAStockPage() {
        shadowOf(pm).addResolveInfoForIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = oem.packageName
                name = oem.className
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply { packageName = oem.packageName; enabled = true }
            }
        })
        val plan = BluetoothSettingsNavigation.inspect(pm)
        assertEquals(oem, plan.shortcutHandler)
        assertTrue(plan.bluetoothPages.isEmpty())
    }

    @Test fun launchPrefersTheValidatedPageAndUsesNoPrivateExtrasOrFlags() {
        val started = mutableListOf<Intent>()
        val result = BluetoothSettingsNavigation.launch(SettingsPlan(listOf(page, alias), oem)) { started += it }
        assertEquals(SettingsLaunchResult(SettingsDestination.ANDROID_BLUETOOTH_PAGE, page), result)
        assertEquals(1, started.size)
        assertEquals(page, started.single().component)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, started.single().action)
        assertNull(started.single().extras)
        assertEquals(0, started.single().flags)
    }

    @Test fun aPageRemovedAfterInspectionFallsBackToTheAlias() {
        val started = mutableListOf<Intent>()
        val result = BluetoothSettingsNavigation.launch(SettingsPlan(listOf(page, alias))) {
            started += it
            if (it.component == page) throw ActivityNotFoundException()
        }
        assertEquals(listOf(page, alias), started.map { it.component })
        assertEquals(SettingsLaunchResult(SettingsDestination.ANDROID_BLUETOOTH_PAGE, alias), result)
    }

    @Test fun securityBlockedPagesFallBackToTheImplicitBluetoothShortcut() {
        val started = mutableListOf<Intent>()
        val result = BluetoothSettingsNavigation.launch(SettingsPlan(listOf(page, alias), oem)) {
            started += it
            if (it.component != null) throw SecurityException()
        }
        assertEquals(listOf(page, alias, null), started.map { it.component })
        assertEquals(SettingsLaunchResult(SettingsDestination.BLUETOOTH_SHORTCUT, oem), result)
    }

    @Test fun anUnknownOrInvisibleResolverDoesNotPreventLaunchingTheShortcut() {
        val plan = BluetoothSettingsNavigation.inspect(pm)
        assertNull(plan.shortcutHandler)
        val started = mutableListOf<Intent>()
        val result = BluetoothSettingsNavigation.launch(plan) { started += it }
        assertEquals(SettingsLaunchResult(SettingsDestination.BLUETOOTH_SHORTCUT), result)
        assertEquals(listOf(Settings.ACTION_BLUETOOTH_SETTINGS), started.map { it.action })
        assertNull(started.single().component)
    }

    @Test fun missingOrDeniedShortcutFallsBackToGeneralSettings() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val started = mutableListOf<Intent>()
            val result = BluetoothSettingsNavigation.launch(SettingsPlan()) {
                started += it
                if (it.action == Settings.ACTION_BLUETOOTH_SETTINGS) throw failure
            }
            assertEquals(listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS), started.map { it.action })
            assertEquals(SettingsLaunchResult(SettingsDestination.GENERAL_SETTINGS), result)
            assertTrue(started.all { it.component == null && it.extras == null && it.flags == 0 })
        }
    }

    @Test fun noAvailableSettingsActivityReturnsAGracefulUnavailableResult() {
        val started = mutableListOf<Intent>()
        val result = BluetoothSettingsNavigation.launch(SettingsPlan(listOf(page))) {
            started += it
            if (it.component != null) throw SecurityException()
            throw ActivityNotFoundException()
        }
        assertEquals(3, started.size)
        assertEquals(SettingsLaunchResult(SettingsDestination.UNAVAILABLE), result)
    }
}
