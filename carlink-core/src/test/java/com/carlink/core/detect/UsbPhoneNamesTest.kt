package com.carlink.core.detect

import com.carlink.core.connection.ConnectionPolicy
import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPhoneNamesTest {
    @Test fun wiredReceiverNeverLooksUpBluetoothNamesForACable() {
        val name = UsbPhoneNames.resolve(PhoneKind.IPHONE, "Apple", ConnectionPolicy.USB_CARPLAY_ONLY) {
            error("Bluetooth lookup must not run on wired startup")
        }
        assertEquals("iPhone", name)
    }

    @Test fun genericReceiverStillUsesAPairedNameWhenAvailable() {
        assertEquals("Driver's iPhone", UsbPhoneNames.resolve(PhoneKind.IPHONE, "Apple", ConnectionPolicy.ALL) {
            "Driver's iPhone"
        })
        assertEquals("iPhone", UsbPhoneNames.resolve(PhoneKind.IPHONE, "Apple", ConnectionPolicy.ALL) { null })
    }
}
