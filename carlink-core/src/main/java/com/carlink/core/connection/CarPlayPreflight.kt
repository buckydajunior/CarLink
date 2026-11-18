package com.carlink.core.connection

import android.content.Context
import android.net.VpnService
import com.shilapi.xcertplay.CarPlayHostIntegration

/** Pure result collection keeps both missing authentication and consent visible to the host. */
object CarPlayPreflight {
    data class Input(val authenticationReady: Boolean, val microphoneGranted: Boolean, val vpnConsentGranted: Boolean)
    fun check(input: Input): List<Problem> = buildList {
        if (!input.microphoneGranted) add(Problem.PERMISSIONS)
        if (!input.authenticationReady) add(Problem.CARPLAY_AUTH_MISSING)
        if (!input.vpnConsentGranted) add(Problem.VPN_CONSENT)
    }
    fun check(context: Context): List<Problem> = check(Input(
        authenticationReady = CarPlayHostIntegration.authenticationError(context) == null,
        microphoneGranted = DevicePreflight.missingPermissions(context).isEmpty(),
        vpnConsentGranted = VpnService.prepare(context) == null,
    ))
}
