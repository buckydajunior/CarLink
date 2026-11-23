package com.carlink.core.entry

import android.app.Activity
import android.content.Intent
import android.content.Context
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
import com.carlink.core.connection.SessionState
import java.lang.ref.WeakReference

/**
 * CarLink's only USB_DEVICE_ATTACHED handler (both engines' filters are removed from the merged
 * manifest). Accepting the system prompt here grants USB access to the whole package, so the
 * CarPlay engine can open the phone. Peripherals are ignored without showing anything.
 */
class UsbRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        val device = if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbDevice(intent) else null
        if (device == null || PhoneClassifier.classifyUsb(describe(device)) != PhoneKind.IPHONE) {
            finish()
            return
        }
        // Retain only the actual USB event while locked. Application initialization is queued
        // before this work; no Connection, preferences or authentication may be touched early.
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        val app = applicationContext
        waitingRouter?.get()?.takeIf { it !== this }?.finish()
        val owner = WeakReference(this)
        waitingRouter = owner
        AfterUserUnlock.run(app, USB_KEY) { deferred ->
            val activity = owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            try {
                com.carlink.core.diagnostics.EventLog.add("USB", "system attach handoff; deferredUntilUnlock=$deferred")
                // A parked phone can disappear or re-enumerate before Android finishes booting.
                val current = if (deferred) UsbWatcher.get(app).currentIphoneAttach(attach) else attach
                if (current != null) routeUnlocked(activity ?: app, current)
            } finally {
                if (waitingRouter === owner) waitingRouter = null
                activity?.finish()
            }
        }
    }

    override fun onDestroy() {
        if (waitingRouter?.get() === this) waitingRouter = null
        // The application owns the queued attach; destruction of this invisible Activity must
        // not lose it. The callback retains only a weak Activity reference and refreshes USB.
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarLink-USB"
        private val USB_KEY = Any()
        private var waitingRouter: WeakReference<UsbRouterActivity>? = null

        private fun routeUnlocked(context: Context, intent: Intent) {
            val device = usbDevice(intent) ?: return
            val kind = PhoneClassifier.classifyUsb(describe(device))
            Log.i(TAG, "USB ${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
            com.carlink.core.diagnostics.EventLog.add("USB", "${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
            if (kind != PhoneKind.IPHONE) return
            val session = Connection.get(context)
            // A cold attach may already be claimed by Application's watcher, with preflight blocked
            // or projection waiting for an Activity to resume. Open Home while this system launch
            // allows it, but never cover an existing host during the engine's USB re-enumeration.
            if (session.needsHostUi()) {
                CarLinkCore.config.host.openHome(context, CarLinkHost.OpenReason.USB_ATTACHED)
            } else if ((session.state.value as? SessionState.Active)?.phase == SessionState.Phase.Connected) {
                CarLinkCore.config.host.openProjection(context)
            }
            if (!UsbWatcher.get(context).claim(device)) {
                Log.i(TAG, "USB attach already routed by the watcher")
                return
            }
            // The attach intent carries the UsbDevice: DiPlay reads it for a wired iPhone, and the engine can find that exact device.
            if (!session.onUsbPhone(kind, "iPhone", intent)) UsbWatcher.get(context).release(device)
        }

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
