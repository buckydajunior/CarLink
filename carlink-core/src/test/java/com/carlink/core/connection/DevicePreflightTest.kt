package com.carlink.core.connection
import org.junit.Assert.assertEquals
import org.junit.Test
class DevicePreflightTest {
    @Test fun wiredRuntimeAccessContainsOnlyTheMicrophone() {
        assertEquals(listOf(android.Manifest.permission.RECORD_AUDIO), DevicePreflight.requiredPermissions())
    }
}
