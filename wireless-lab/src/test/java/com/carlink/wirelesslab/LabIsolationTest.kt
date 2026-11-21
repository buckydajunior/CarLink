package com.carlink.wirelesslab

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the merged APK manifest, including permissions and components from dependencies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class LabIsolationTest {
    @Before fun resetProviderRoots() = resetReportProviderCache()
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.packageManager

    @Test
    fun separateIdentityHasOneOrdinaryLauncher() {
        assertEquals(LAB_PACKAGE, context.packageName)
        assertNotEquals("com.buckydajunior.carlink.f150", context.packageName)
        val launchers = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(LAB_PACKAGE),
            0,
        )
        assertEquals(1, launchers.size)
        val activity = launchers.single().activityInfo
        assertEquals("com.carlink.wirelesslab.MainActivity", activity.name)
        assertTrue(activity.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activity.launchMode)
        val info = manager.getPackageInfo(LAB_PACKAGE, 0)
        assertEquals("0.1.3", info.versionName)
        @Suppress("DEPRECATION")
        assertEquals(103, info.versionCode)
    }

    @Test
    fun permissionsAreLimitedToBluetoothAndNetworkInspection() {
        val requested = manager.getPackageInfo(LAB_PACKAGE, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        // AndroidX Core declares this private signature permission for safe dynamic receivers.
        val corePrivatePermission = "$LAB_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val permitted = setOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_WIFI_STATE",
            corePrivatePermission,
        )
        assertTrue("Unexpected permission(s): ${requested - permitted}", permitted.containsAll(requested))
        assertTrue(requested.contains("android.permission.ACCESS_WIFI_STATE"))
        assertFalse(requested.contains("android.permission.ACCESS_NETWORK_STATE"))
        val bluetoothPermission = if (Build.VERSION.SDK_INT >= 31) {
            "android.permission.BLUETOOTH_CONNECT"
        } else {
            "android.permission.BLUETOOTH"
        }
        assertTrue(requested.contains(bluetoothPermission))
        assertFalse(requested.contains("android.permission.INTERNET"))
        assertFalse(requested.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertFalse(requested.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(requested.contains("android.permission.BLUETOOTH_SCAN"))
        assertFalse(requested.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertFalse(requested.contains("android.permission.CHANGE_WIFI_STATE"))
        assertFalse(requested.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(requested.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(requested.contains("android.permission.NEARBY_WIFI_DEVICES"))
    }

    @Test
    fun neverClaimsUsbStartupOrBackgroundProjection() {
        val info = manager.getPackageInfo(
            LAB_PACKAGE,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES,
        )
        assertEquals(listOf("com.carlink.wirelesslab.MainActivity"), info.activities.orEmpty().map { it.name })
        assertTrue(info.receivers.orEmpty().isEmpty())
        assertTrue(info.services.orEmpty().isEmpty())
        assertTrue(manager.queryIntentActivities(
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).setPackage(LAB_PACKAGE), 0,
        ).isEmpty())
        assertTrue(manager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(LAB_PACKAGE), 0,
        ).isEmpty())
        val features = manager.getPackageInfo(LAB_PACKAGE, PackageManager.GET_CONFIGURATIONS)
            .reqFeatures.orEmpty().associateBy { it.name }
        for (feature in listOf(
            PackageManager.FEATURE_BLUETOOTH,
            PackageManager.FEATURE_WIFI,
            PackageManager.FEATURE_WIFI_DIRECT,
        )) {
            assertTrue("Missing optional feature: $feature", features.containsKey(feature))
            assertEquals(0, features.getValue(feature).flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
        }
    }

    @Test
    fun reportProviderSharesOnlyItsDedicatedCacheDirectory() {
        val providers = manager.getPackageInfo(LAB_PACKAGE, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertEquals(1, providers.size)
        val provider = providers.single()
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertEquals("$LAB_PACKAGE.reports", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val report = File(context.cacheDir, "reports/compatibility.txt")
        report.parentFile!!.mkdirs()
        report.writeText("test report")
        val uri = FileProvider.getUriForFile(context, provider.authority, report)
        assertEquals("content", uri.scheme)
        assertEquals(provider.authority, uri.authority)
        try {
            FileProvider.getUriForFile(context, provider.authority, File(context.cacheDir, "private.txt"))
            fail("The provider must not share files outside cache/reports")
        } catch (_: IllegalArgumentException) {
            // The exported report scope is deliberately narrower than the whole cache.
        }
    }

    @Test
    fun includesNoticesWithoutCarPlayRuntimeOrIdentity() {
        val notices = context.assets.list("carlink-licenses").orEmpty().toSet()
        assertEquals(setOf("AGPL-3.0.txt", "GPL-3.0.txt", "NOTICE.txt"), notices)
        assertTrue(context.assets.open("carlink-licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() }
            .contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
        assertFalse(context.assets.list("").orEmpty().contains("offline-mfi"))
        try {
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController")
            fail("The compatibility app must not package the projection engine")
        } catch (_: ClassNotFoundException) {
            // The lab can coexist with the wired app without initializing any engine.
        }
    }

    private companion object {
        const val LAB_PACKAGE = "com.buckydajunior.carlink.f150.wirelesslab"
    }
}

/** AndroidX keeps static roots while Robolectric gives each test a new app data directory. */
internal fun resetReportProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    val cache = field.get(null) as MutableMap<*, *>
    synchronized(cache) { cache.clear() }
}
