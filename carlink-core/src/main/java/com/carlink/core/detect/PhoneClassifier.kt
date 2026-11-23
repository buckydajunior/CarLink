package com.carlink.core.detect

import com.carlink.core.core.PhoneKind

/** Only an Apple phone is eligible for USB CarPlay; all other USB hardware is ignored. */
object PhoneClassifier {
    const val APPLE_VENDOR_ID = 0x05AC
    data class UsbDeviceInfo(val vendorId: Int, val productId: Int, val productName: String? = null)
    fun isAppleNonPhone(device: UsbDeviceInfo): Boolean = device.vendorId == APPLE_VENDOR_ID && (
        device.productName?.let { it.contains("iPad", true) || it.contains("iPod", true) }
            ?: (device.productId == 0x12AB || device.productId == 0x12AA))
    fun classifyUsb(device: UsbDeviceInfo): PhoneKind =
        if (device.vendorId == APPLE_VENDOR_ID && !isAppleNonPhone(device)) PhoneKind.IPHONE else PhoneKind.UNKNOWN
}
