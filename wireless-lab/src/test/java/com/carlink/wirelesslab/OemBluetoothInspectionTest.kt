package com.carlink.wirelesslab

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class OemBluetoothInspectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun install(name: String, label: String, services: Int = 1, channel: String? = null) {
        val app = ApplicationInfo().apply {
            packageName = name
            nonLocalizedLabel = label
            enabled = true
            metaData = Bundle().apply {
                putString("private_phone_name", "SECRET_PHONE")
                putString("private_key", "SECRET_KEY")
                channel?.let { putString("TD_CHANNEL_ID", it) }
            }
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = app
            versionName = "4.0.34"
            @Suppress("DEPRECATION")
            versionCode = 40034
            this.services = Array(services) { index ->
                ServiceInfo().apply {
                    packageName = name
                    this.name = "$name.BluetoothService$index"
                    applicationInfo = app
                    exported = false
                    enabled = true
                    permission = "com.example.permission.BLUETOOTH_BRIDGE"
                }
            }
        })
    }

    private fun collect(node: File = object : File("/metadata-only") {
        override fun exists() = true
        override fun canRead() = false
        override fun canWrite() = false
    }) = OemBluetoothInspection(context, node).collect()

    @Test fun identifiesMatchingAppsAndOnlyKnownChannelWithoutPrivateMetadata() {
        install("com.zjinnova.zlink", "ZLink", channel = "zhuoxw")
        install("com.example.btsuite", "Phone")
        install("com.example.weather", "PRIVATE_WEATHER")
        val report = CompatibilityReport(collect()).format(context)
        assertTrue(report.contains("com.example.btsuite.BluetoothService0"))
        assertTrue(report.contains("exported: No"))
        assertTrue(report.contains("TD_CHANNEL_ID: zhuoxw"))
        assertFalse(report.contains("SECRET_PHONE"))
        assertFalse(report.contains("SECRET_KEY"))
        assertFalse(report.contains("private_phone_name"))
        assertFalse(report.contains("PRIVATE_WEATHER"))
        assertFalse(report.contains("com.example.weather"))
        assertTrue(report.contains("Candidates are not confirmed Bluetooth interfaces"))
        assertTrue(report.contains("not installed or not visible"))
    }

    @Test fun nodeSecurityFailureDoesNotHidePackageResultsOrLeakExceptionMessage() {
        install("com.example.bt", "Bluetooth")
        val readings = collect(object : File("/metadata-only") {
            override fun exists(): Boolean = throw SecurityException("SECRET_PHONE")
        })
        assertEquals(Reading.PermissionDenied, readings[Check.OEM_SERIAL_NODE])
        assertTrue((readings[Check.OEM_APPS] as Reading.Text).value.contains("com.example.bt"))
        assertFalse(CompatibilityReport(readings).format(context).contains("SECRET_PHONE"))
    }

    @Test fun nodeMetadataDoesNotRequireOpeningThePathAndNeverClaimsWorkingTransport() {
        // Deliberately not a real path. Only these three metadata methods can succeed.
        val report = CompatibilityReport(collect()).format(context)
        assertTrue(report.contains("Visible: Yes; readable: No; writable: No"))
        assertTrue(report.contains("node never opened"))
        assertTrue(report.contains("do not prove access or a usable transport"))
        assertFalse(report.contains("/metadata-only"))
    }

    @Test fun invalidChannelAndControlCharactersCannotInjectReportLines() {
        install("com.zjinnova.zlink", "ZLink\nFake: Yes", channel = "secret\nPHONE")
        val report = CompatibilityReport(collect()).format(context)
        assertTrue(report.contains("ZLink Fake: Yes"))
        assertFalse(report.contains("ZLink\nFake: Yes"))
        assertTrue(report.contains("TD_CHANNEL_ID: Not reported"))
        assertFalse(report.contains("secret"))
    }

    @Test fun candidateAndServiceOverflowAreExplicitlyBounded() {
        repeat(18) { install("com.example.bluetooth%02d".format(it), "Bluetooth", services = 30) }
        val text = (collect()[Check.OEM_APPS] as Reading.Text).value
        assertTrue(text.contains("com.example.bluetooth00"))
        assertFalse(text.contains("com.example.bluetooth17"))
        assertEquals(12 * 24, Regex("Service:").findAll(text).count())
        assertTrue(text.contains("Additional candidates or services omitted"))
    }

    @Test fun candidateNamesIncludeAlternateBtPackagesWithoutMatchingArbitraryBtSubstrings() {
        assertTrue(OemBluetoothInspection.matchesCandidate("com.example.btsuite", "Phone"))
        assertTrue(OemBluetoothInspection.matchesCandidate("com.example.bt", "Phone"))
        assertTrue(OemBluetoothInspection.matchesCandidate("com.example.bt.ui", "Phone"))
        assertFalse(OemBluetoothInspection.matchesCandidate("com.example.debt", "Calculator"))
        assertFalse(OemBluetoothInspection.matchesCandidate("com.zxw.radio", "Radio"))
    }

    @Test fun defaultHomeIsIncludedEvenWhenItsNameDoesNotMentionBluetooth() {
        install("com.example.dashboard", "Dashboard")
        val app = context.packageManager.getApplicationInfo("com.example.dashboard", 0)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                applicationInfo = app
                packageName = app.packageName
                name = "com.example.dashboard.Home"
                enabled = true
                exported = true
            }
        }
        shadowOf(context.packageManager).addOrUpdateActivity(info.activityInfo)
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), info)
        val readings = collect()
        assertEquals(Reading.Text("com.example.dashboard/.Home"), readings[Check.OEM_HOME])
        assertTrue((readings[Check.OEM_APPS] as Reading.Text).value.contains("Dashboard — com.example.dashboard"))
    }

    @Test fun absentHomeResolutionIsUnknownRatherThanAnInventedLauncher() {
        assertEquals(Reading.Unavailable, collect()[Check.OEM_HOME])
    }

    @Test fun enumerationFailureRetainsKnownPackagesWithoutLeakingFailureMessage() {
        install("com.zjinnova.zlink", "ZLink", channel = "zhuoxw")
        val report = CompatibilityReport(OemBluetoothInspection(context,
            enumerateApplications = { throw SecurityException("SECRET_PHONE") }).collect()).format(context)
        assertTrue(report.contains("TD_CHANNEL_ID: zhuoxw"))
        assertTrue(report.contains("Known package checks are retained"))
        assertTrue(report.contains("SecurityException"))
        assertFalse(report.contains("SECRET_PHONE"))
    }
}
