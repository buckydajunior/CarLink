package com.carlink.core.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.PhoneClassifier
import com.carlink.core.connection.Connection

/**
 * Finds phones on USB while CarLink is open, without relying on the system's attach dispatch.
 *
 * Android only launches UsbRouterActivity when CarLink wins the USB_DEVICE_ATTACHED resolution.
 * An iPhone also exposes a PTP (photo) interface, so Photos and the MTP host compete for it and
 * the system may show a chooser or pick another app. While CarLink runs, it therefore scans the
 * connected devices and listens for attach/detach itself; each engine then asks for USB access.
 */
class UsbWatcher private constructor(context: Context) {
    private val app = context.applicationContext
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private val handled = mutableSetOf<String>()
    private var registered = false
    private val main = Handler(Looper.getMainLooper())
    private var settling = false
    private var nextScan = 0
    private var recoverHandled = false
    private var lastWakeAt = -WAKE_COALESCE_MILLIS
    private val settlingScan = Runnable {
        if (registered && settling && !scan()) scheduleNextScan()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = UsbRouterActivity.usbDevice(intent) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (route(device)) finishSettling()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    handled -= device.deviceName
                    Connection.get(app).onUsbDetached()
                }
            }
        }
    }

    /**
     * Starts watching (once per process) and routes phones already plugged in. Called when the
     * main process starts and again whenever Home opens, so a phone plugged in while CarLink was
     * closed is still picked up.
     */
    fun start() = startScanning(recover = false)

    /** A visible host can recover an already-handled failed attempt without a cable replug. */
    fun onHomeShown() = startScanning(recover = true)

    /** Coalesce the several boot/ACC broadcasts a stereo can send for one wake. */
    fun onWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < WAKE_COALESCE_MILLIS) return
        lastWakeAt = now
        finishSettling()
        startScanning(recover = true)
    }

    private fun startScanning(recover: Boolean) {
        if (!registered) {
            registered = true
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        recoverHandled = recoverHandled || recover
        if (scan() || settling) return
        settling = true
        nextScan = 0
        com.carlink.core.diagnostics.EventLog.add("USB", "waiting for startup USB enumeration (up to 30 s)")
        scheduleNextScan()
    }

    private fun listedDevices(): List<UsbDevice> = runCatching { usb?.deviceList?.values?.toList().orEmpty() }
        .getOrElse {
            Log.w(TAG, "USB enumeration not ready; waiting for the next scan", it)
            emptyList()
        }

    private fun scan(): Boolean {
        val present = listedDevices()
        handled.retainAll(present.map { it.deviceName }.toSet())
        var phoneFound = false
        for (device in present) {
            if (route(device, fromScan = true, recover = recoverHandled && !phoneFound)) phoneFound = true
        }
        if (phoneFound) finishSettling()
        return phoneFound
    }

    private fun scheduleNextScan() {
        if (nextScan >= STARTUP_SCAN_DELAYS_MILLIS.size) {
            finishSettling()
            com.carlink.core.diagnostics.EventLog.add("USB", "startup USB scan ended; no iPhone listed")
        } else main.postDelayed(settlingScan, STARTUP_SCAN_DELAYS_MILLIS[nextScan++])
    }

    private fun finishSettling() {
        main.removeCallbacks(settlingScan)
        settling = false
        recoverHandled = false
    }

    /** Fresh USB-only presence check for Home and unplug handling; never reads phone names or radios. */
    fun hasIphone(): Boolean = listedDevices().any { device ->
        device.vendorId == PhoneClassifier.APPLE_VENDOR_ID &&
            PhoneClassifier.classifyUsb(UsbRouterActivity.describe(device)) == PhoneKind.IPHONE
    }

    fun stop() {
        finishSettling()
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    /**
     * Marks this attach as routed; false when it already was. The system's attach dispatch
     * (UsbRouterActivity) and this watcher both see a plug-in while CarLink runs, and a later
     * scan (Home opening) must not start a second time for a phone that is still plugged in.
     */
    fun claim(device: UsbDevice): Boolean = handled.add(device.deviceName)

    private fun route(device: UsbDevice, fromScan: Boolean = false, recover: Boolean = false): Boolean {
        val fresh = claim(device)
        val info = UsbRouterActivity.describe(device)
        val kind = PhoneClassifier.classifyUsb(info)
        if (fresh) {
            if (PhoneClassifier.isAppleNonPhone(info)) {
                android.widget.Toast.makeText(app, com.carlink.core.R.string.carlink_core_ipad_no_carplay,
                    android.widget.Toast.LENGTH_LONG).show()
            }
            val message = "USB ${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind"
            Log.i(TAG, message)
            com.carlink.core.diagnostics.EventLog.add("USB", message)
        }
        if (kind != PhoneKind.IPHONE) return false
        if (fresh || recover) {
            val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
            if (fromScan) {
                val accepted = Connection.get(app).onUsbReady(kind, "iPhone", attach)
                // A paused scan must not consume a later real system attach for a new node.
                if (fresh && !accepted) handled -= device.deviceName
            } else Connection.get(app).onUsbPhone(kind, "iPhone", attach)
        }
        return true
    }

    companion object {
        private const val TAG = "CarLink-USB"
        private const val WAKE_COALESCE_MILLIS = 3_000L
        // Cumulative scans at 0.5, 1.5, 3, 5, 10, 20 and 30 seconds; never a permanent poll.
        private val STARTUP_SCAN_DELAYS_MILLIS = longArrayOf(500, 1_000, 1_500, 2_000, 5_000, 10_000, 10_000)

        @Volatile private var instance: UsbWatcher? = null

        /** Lives as long as CarLink's main process, which also hosts the CarPlay picture. */
        fun get(context: Context): UsbWatcher =
            instance ?: synchronized(this) { instance ?: UsbWatcher(context).also { instance = it } }
    }
}
