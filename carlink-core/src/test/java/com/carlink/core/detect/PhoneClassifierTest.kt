package com.carlink.core.detect
import com.carlink.core.core.PhoneKind
import org.junit.Assert.*
import org.junit.Test
class PhoneClassifierTest {
    @Test fun iphoneIsAcceptedBeforeAndAfterUsbReenumeration() {
        for (id in listOf(0x12A8, 0x12A9, 0x12AF)) assertEquals(PhoneKind.IPHONE,
            PhoneClassifier.classifyUsb(PhoneClassifier.UsbDeviceInfo(0x05AC, id)))
    }
    @Test fun ipadsAndIpodsDoNotStartAnEndlessCarPlayAttempt() {
        for (device in listOf(PhoneClassifier.UsbDeviceInfo(0x05AC, 0x12AB), PhoneClassifier.UsbDeviceInfo(0x05AC, 0x12AA),
            PhoneClassifier.UsbDeviceInfo(0x05AC, 0x1234, "iPad Pro"), PhoneClassifier.UsbDeviceInfo(0x05AC, 0x1234, "iPod"))) {
            assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(device))
        }
    }
    @Test fun androidAccessoryAndOtherPeripheralsAreIgnored() {
        for (vendor in listOf(0x18D1, 0x04E8, 0x2717, 0x0BDA, 0x1A86)) assertEquals(PhoneKind.UNKNOWN,
            PhoneClassifier.classifyUsb(PhoneClassifier.UsbDeviceInfo(vendor, 0x2D00)))
    }
}
