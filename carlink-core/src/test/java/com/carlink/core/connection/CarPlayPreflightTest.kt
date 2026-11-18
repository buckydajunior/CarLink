package com.carlink.core.connection
import org.junit.Assert.*
import org.junit.Test
class CarPlayPreflightTest {
    @Test fun readyUsbNeedsNoOtherPrerequisite() {
        assertEquals(emptyList<Problem>(), CarPlayPreflight.check(CarPlayPreflight.Input(true, true, true)))
    }
    @Test fun missingRequirementsAreReportedTogetherWithPermissionFirst() {
        assertEquals(listOf(Problem.PERMISSIONS, Problem.CARPLAY_AUTH_MISSING, Problem.VPN_CONSENT),
            CarPlayPreflight.check(CarPlayPreflight.Input(false, false, false)))
        assertEquals(listOf(Problem.VPN_CONSENT), CarPlayPreflight.check(CarPlayPreflight.Input(true, true, false)))
        assertEquals(listOf(Problem.CARPLAY_AUTH_MISSING), CarPlayPreflight.check(CarPlayPreflight.Input(false, true, true)))
    }
}
