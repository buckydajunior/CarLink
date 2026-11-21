package com.carlink.wirelesslab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var screen: LabScreen
    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "wireless-lab-check").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var report: String? = null
    private var busy = false
    private var resumed = false
    private var refreshAfterSettings = false
    private var lastSettingsRequest: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = savedInstanceState?.getString(REPORT)
        refreshAfterSettings = savedInstanceState?.getBoolean(SETTINGS_RETURN) ?: false
        lastSettingsRequest = savedInstanceState?.getString(SETTINGS_REQUEST)
        screen = LabScreen(this).apply {
            onCheck = ::checkWithPermission
            onShare = ::shareReport
            onSettings = ::openBluetoothSettings
            onAbout = ::about
        }
        setContentView(screen)
        if (Build.VERSION.SDK_INT >= 35) {
            screen.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            screen.requestApplyInsets()
        }
        screen.showState(report, busy = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (refreshAfterSettings && !busy) {
            refreshAfterSettings = false
            collect() // Passive refresh only, after the user returns from the settings shortcut.
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun openBluetoothSettings() {
        if (busy) return
        busy = true
        screen.showState(report, true, R.string.carlink_lab_opening_settings)
        worker.execute {
            // Metadata lookup stays off the UI thread. Hidden query results must not prevent
            // an otherwise permitted startActivity call on newer Android versions.
            val plan = runCatching { BluetoothSettingsNavigation.inspect(packageManager) }
                .getOrElse { SettingsPlan() }
            deliver {
                busy = false
                if (!resumed) {
                    screen.showState(report, false)
                    return@deliver // Do not open a delayed settings page after the user leaves.
                }
                val result = BluetoothSettingsNavigation.launch(plan, ::startActivity)
                val destination = getString(when (result.destination) {
                    SettingsDestination.ANDROID_BLUETOOTH_PAGE -> R.string.carlink_lab_request_android_page
                    SettingsDestination.BLUETOOTH_SHORTCUT -> R.string.carlink_lab_request_shortcut
                    SettingsDestination.GENERAL_SETTINGS -> R.string.carlink_lab_request_general
                    SettingsDestination.UNAVAILABLE -> R.string.carlink_lab_request_unavailable
                })
                lastSettingsRequest = getString(R.string.carlink_lab_last_settings_request, destination,
                    result.component?.flattenToShortString() ?: getString(R.string.carlink_lab_implicit_target))
                refreshAfterSettings = result.destination != SettingsDestination.UNAVAILABLE
                screen.showState(report, false, when (result.destination) {
                    SettingsDestination.ANDROID_BLUETOOTH_PAGE, SettingsDestination.BLUETOOTH_SHORTCUT ->
                        R.string.carlink_lab_settings_return
                    SettingsDestination.GENERAL_SETTINGS -> R.string.carlink_lab_settings_fallback
                    SettingsDestination.UNAVAILABLE -> R.string.carlink_lab_settings_unavailable
                })
                if (result.destination == SettingsDestination.UNAVAILABLE) {
                    // There will be no return/resume event. Capture the failed request now so
                    // Share report immediately includes it, while keeping the failure status.
                    collect(R.string.carlink_lab_settings_unavailable)
                }
            }
        }
    }

    private fun checkWithPermission() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
        } else collect()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Denial is useful data too; Wi-Fi/device checks still run.
        if (requestCode == BLUETOOTH_REQUEST && !closed && !busy) collect()
    }

    private fun collect(statusAfterSuccess: Int? = null) {
        busy = true
        screen.showState(report, busy = true)
        val settingsRequest = lastSettingsRequest
        worker.execute {
            val result = runCatching {
                val readings = CompatibilityProbe(AndroidRadioAccess(applicationContext)).collect().readings +
                    OemBluetoothInspection(applicationContext).collect()
                CompatibilityReport(readings).format(this) +
                    (settingsRequest?.let { "\n$it\n" } ?: "")
            }
            deliver {
                busy = false
                result.onSuccess { report = it }
                screen.showState(report, busy = false,
                    message = if (result.isFailure) R.string.carlink_lab_check_failed else statusAfterSuccess)
            }
        }
    }

    private fun shareReport() {
        val snapshot = report ?: return
        if (busy) return
        busy = true
        screen.showState(report, busy = true, message = R.string.carlink_lab_complete)
        worker.execute {
            val result = runCatching {
                val dir = File(cacheDir, "reports").apply { check(isDirectory || mkdirs()) }
                val file = File(dir, "compatibility-${System.currentTimeMillis()}.txt")
                file.writeText(snapshot, Charsets.UTF_8)
                dir.listFiles().orEmpty().filter { it != file }.sortedByDescending { it.lastModified() }
                    .drop(4).forEach { it.delete() }
                FileProvider.getUriForFile(this, "$packageName.reports", file)
            }
            deliver {
                busy = false
                screen.showState(report, busy = false)
                result.onSuccess { uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(null, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.carlink_lab_share_chooser)))
                    } catch (_: Exception) {
                        screen.showState(report, false, R.string.carlink_lab_share_failed)
                    }
                }.onFailure { screen.showState(report, false, R.string.carlink_lab_share_failed) }
            }
        }
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle(R.string.carlink_lab_app_name)
            .setMessage(getString(R.string.carlink_lab_about_text, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.carlink_lab_close, null)
            .setNeutralButton(R.string.carlink_lab_license_menu) { _, _ -> licenseMenu() }
            .show()
    }

    private fun licenseMenu() {
        val labels = arrayOf(
            getString(R.string.carlink_lab_agpl), getString(R.string.carlink_lab_gpl),
            getString(R.string.carlink_lab_notices),
        )
        val files = arrayOf("AGPL-3.0.txt", "GPL-3.0.txt", "NOTICE.txt")
        AlertDialog.Builder(this).setTitle(R.string.carlink_lab_license_menu)
            .setItems(labels) { _, which ->
                worker.execute {
                    val text = runCatching {
                        assets.open("carlink-licenses/${files[which]}").bufferedReader().use { it.readText() }
                    }.getOrElse { getString(R.string.carlink_lab_license_failed) }
                    deliver {
                        val body = TextView(this).apply {
                            this.text = text
                            textSize = 18f
                            setPadding(24, 16, 24, 16)
                            setTextIsSelectable(true)
                        }
                        AlertDialog.Builder(this).setTitle(labels[which])
                            .setView(ScrollView(this).apply { addView(body) })
                            .setPositiveButton(R.string.carlink_lab_close, null).show()
                    }
                }
            }.show()
    }

    private fun deliver(action: () -> Unit) {
        if (!closed) runOnUiThread { if (!closed && !isFinishing && !isDestroyed) action() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(REPORT, report)
        outState.putBoolean(SETTINGS_RETURN, refreshAfterSettings)
        outState.putString(SETTINGS_REQUEST, lastSettingsRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val BLUETOOTH_REQUEST = 1
        const val REPORT = "compatibility_report"
        const val SETTINGS_RETURN = "refresh_after_settings"
        const val SETTINGS_REQUEST = "last_settings_request"
    }
}
