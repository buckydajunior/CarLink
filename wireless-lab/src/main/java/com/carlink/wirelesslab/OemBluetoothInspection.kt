package com.carlink.wirelesslab

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.Locale

/** Package/component declarations and file permission metadata only. Never opens a device node,
 * loads another APK, binds a service, reads system properties, or contacts the OEM radio. */
internal class OemBluetoothInspection(
    private val context: Context,
    private val serialNode: File = File("/dev/rf_serial"),
    private val enumerateApplications: () -> List<ApplicationInfo> = {
        context.packageManager.getInstalledApplications(0)
    },
) {
    private val manager = context.packageManager

    fun collect(): Map<Check, Reading> = linkedMapOf(
        Check.OEM_HOME to capture {
            defaultHome()?.flattenToShortString()?.let(Reading::Text) ?: Reading.Unavailable
        },
        Check.OEM_APPS to capture { Reading.Text(apps()) },
        Check.OEM_SERIAL_NODE to capture {
            Reading.Text(context.getString(R.string.carlink_lab_node_metadata,
                yesNo(serialNode.exists()), yesNo(serialNode.canRead()), yesNo(serialNode.canWrite())))
        },
    )

    @Suppress("DEPRECATION") // Integer flag APIs also work on this API27 head unit.
    private fun apps(): String = buildString {
        appendLine(context.getString(R.string.carlink_lab_oem_scope))
        val candidates = linkedSetOf("com.android.bluetooth", "com.zjinnova.zlink")
        // The launcher can host the Bluetooth UI. Include only the current default, not all apps.
        val home = runCatching { defaultHome()?.packageName }.getOrNull()
        home?.let(candidates::add)
        val inventory = runCatching { enumerateApplications() }
        val installed = inventory.getOrDefault(emptyList()).filter { it.packageName != context.packageName }
        val matches = installed.asSequence()
            .filter { app ->
                val label = runCatching { manager.getApplicationLabel(app).toString() }.getOrDefault("")
                matchesCandidate(app.packageName, label)
            }
            .map { it.packageName }.distinct().sorted().toList()
        candidates.addAll(matches.take(MAX_CANDIDATES))
        // Manufacturer names alone do not identify a Bluetooth app. Retain a small fallback
        // set after stronger matches because an OEM service may have a generic translated label.
        val fallback = installed.map { it.packageName }.filter { name ->
            val lower = name.lowercase(Locale.ROOT)
            (lower.contains("zhuoxw") || lower.contains("zxw")) && name !in candidates
        }.sorted()
        candidates.addAll(fallback.take(4))
        for (name in candidates) {
            appendLine()
            appendLine(packageSummary(name))
        }
        inventory.exceptionOrNull()?.let { failure ->
            appendLine(context.getString(R.string.carlink_lab_oem_enumeration_error, failure.javaClass.simpleName))
        }
        if (matches.size > MAX_CANDIDATES || fallback.size > 4) {
            appendLine(context.getString(R.string.carlink_lab_oem_truncated))
        }
    }.trimEnd()

    @Suppress("DEPRECATION")
    private fun defaultHome(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // A system chooser is not a selected Home app. Require an actual Home candidate match.
        val candidates = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (candidates.none { it.activityInfo?.let { info ->
                info.packageName == resolved.packageName && info.name == resolved.name
            } == true }) return null
        return ComponentName(resolved.packageName, resolved.name)
    }

    @Suppress("DEPRECATION")
    private fun packageSummary(name: String): String = try {
        val info = manager.getPackageInfo(name,
            PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS or
                (if (name == "com.zjinnova.zlink") PackageManager.GET_META_DATA else 0))
        val app = requireNotNull(info.applicationInfo)
        buildString {
            appendLine(context.getString(R.string.carlink_lab_oem_package,
                clean(manager.getApplicationLabel(app).toString()), clean(name),
                clean(info.versionName.orEmpty()), info.versionCode.toString(),
                yesNo(app.flags and ApplicationInfo.FLAG_SYSTEM != 0), yesNo(app.enabled)))
            // Do not dump metadata: only the known non-identifying channel key on ZLink.
            if (name == "com.zjinnova.zlink") {
                val channel = app.metaData?.getString("TD_CHANNEL_ID")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
                appendLine(context.getString(R.string.carlink_lab_oem_channel,
                    channel ?: context.getString(R.string.carlink_lab_oem_not_reported)))
            }
            val services = info.services.orEmpty().sortedBy { it.name }
            if (services.isEmpty()) appendLine(context.getString(R.string.carlink_lab_oem_no_services))
            for (service in services.take(MAX_SERVICES)) {
                appendLine(context.getString(R.string.carlink_lab_oem_service,
                    clean(service.name), yesNo(service.exported), yesNo(service.enabled),
                    clean(service.permission ?: context.getString(R.string.carlink_lab_oem_none))))
            }
            if (services.size > MAX_SERVICES) appendLine(context.getString(R.string.carlink_lab_oem_truncated))
        }.trimEnd()
    } catch (_: PackageManager.NameNotFoundException) {
        context.getString(R.string.carlink_lab_oem_missing_package, name)
    } catch (_: SecurityException) {
        context.getString(R.string.carlink_lab_oem_package_denied, name)
    } catch (failure: Exception) {
        context.getString(R.string.carlink_lab_oem_package_error, name, failure.javaClass.simpleName)
    }

    private fun yesNo(value: Boolean) = context.getString(if (value) R.string.carlink_lab_yes else R.string.carlink_lab_no)

    private inline fun capture(read: () -> Reading): Reading = try {
        read()
    } catch (_: SecurityException) {
        Reading.PermissionDenied
    } catch (failure: Exception) {
        Reading.Error(failure.javaClass.simpleName)
    }

    private fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").take(200)

    internal companion object {
        private const val MAX_CANDIDATES = 12
        private const val MAX_SERVICES = 24
        fun matchesCandidate(packageName: String, label: String): Boolean {
            val text = "$packageName $label".lowercase(Locale.ROOT)
            return listOf("bluetooth", "blue tooth", "btservice", "btsuite", "zlink",
                "zjinnova", "phonelink", "autokit", "easyconn").any(text::contains) ||
                Regex("(^|[. _-])bt([. _-]|$)").containsMatchIn(text)
        }
    }
}
