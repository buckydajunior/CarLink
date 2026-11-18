package com.carlink.core.connection

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.entry.UsbWatcher

/** No radio, pairing, hotspot, root or car-brand state is read on the USB startup path. */
class DeviceEnvironment(context: Context) : SessionManager.Environment {
    private val app = context.applicationContext
    override fun preflight(request: SessionRequest): List<Problem> = CarPlayPreflight.check(app)
    override fun usbPhonePresent(): Boolean = UsbWatcher.get(app).hasIphone()
    override fun usbSerial(attach: Intent?): String? = runCatching {
        val device = if (Build.VERSION.SDK_INT >= 33) attach?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") attach?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        device?.serialNumber?.takeIf { it.isNotBlank() }
    }.getOrNull()
    override fun returnHome() = CarLinkCore.config.host.openHome(app, CarLinkHost.OpenReason.UNPLUGGED)
    override fun log(message: String) = EventLog.add("CarLink", message)
}
