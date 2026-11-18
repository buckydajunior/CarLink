package com.carlink.core.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/** Resets only the USB device Android has authorized this app to open. No raw device path is used. */
internal object UsbReset {
    private val nativeAvailable by lazy { runCatching { System.loadLibrary("carlink_usb"); true }.getOrDefault(false) }
    fun reset(context: Context, device: UsbDevice): Boolean {
        if (!nativeAvailable) return false
        val connection = runCatching { context.getSystemService(UsbManager::class.java)?.openDevice(device) }.getOrNull() ?: return false
        return try {
            val result = resetFileDescriptor(connection.fileDescriptor)
            if (result != 0) Log.w("CarLink-UsbReset", "USB reset failed: errno ${-result}")
            result == 0
        } catch (error: Throwable) {
            Log.w("CarLink-UsbReset", "USB reset failed", error)
            false
        } finally { connection.close() }
    }
    private external fun resetFileDescriptor(descriptor: Int): Int
}
