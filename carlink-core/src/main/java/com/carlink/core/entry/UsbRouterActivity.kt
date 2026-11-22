package com.carlink.core.entry

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.PhoneClassifier
import com.carlink.core.connection.Connection
import com.carlink.core.connection.CarLinkCore
import com.carlink.core.connection.CarLinkHost

/**
 * CarLink's only USB_DEVICE_ATTACHED handler (both engines' filters are removed from the merged
 * manifest). Accepting the system prompt here grants USB access to the whole package, so the
 * CarPlay engine can open the phone. Peripherals are ignored without showing anything.
 */
class UsbRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
        finish()
    }

    private fun route(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = usbDevice(intent) ?: return
        val kind = PhoneClassifier.classifyUsb(describe(device))
        Log.i(TAG, "USB ${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
        com.carlink.core.diagnostics.EventLog.add("USB", "${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
        if (kind == PhoneKind.UNKNOWN) return
        val session = Connection.get(this)
        // A cold attach may already be claimed by Application's watcher, with preflight blocked
        // or projection waiting for an Activity to resume. Open Home while this system launch
        // allows it, but never cover an existing host during the engine's USB re-enumeration.
        if (session.needsHostUi()) CarLinkCore.config.host.openHome(this, CarLinkHost.OpenReason.USB_ATTACHED)
        if (!UsbWatcher.get(this).claim(device)) {
            Log.i(TAG, "USB attach already routed by the watcher")
            return
        }
        // The attach intent carries the UsbDevice: DiPlay reads it for a wired iPhone, and the engine can find that exact device.
        if (!session.onUsbPhone(kind, "iPhone", intent)) UsbWatcher.get(this).release(device)
    }

    companion object {
        private const val TAG = "CarLink-USB"

        fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        fun describe(device: UsbDevice) = PhoneClassifier.UsbDeviceInfo(
            vendorId = device.vendorId,
            productId = device.productId,
            productName = runCatching { device.productName }.getOrNull(),
        )
    }
}
