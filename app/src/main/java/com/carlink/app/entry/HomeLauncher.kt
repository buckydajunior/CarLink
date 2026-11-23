package com.carlink.app.entry

import android.content.Context
import android.content.Intent
import com.carlink.app.ui.HomeActivity
import com.carlink.core.connection.CarLinkHost
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import java.lang.ref.WeakReference

/** Returns from an unplugged projection to the receiver's USB-ready screen. */
object HomeLauncher : CarLinkHost {
    private var startedHome: WeakReference<HomeActivity>? = null

    internal fun homeStarted(activity: HomeActivity) { startedHome = WeakReference(activity) }
    internal fun homeStopped(activity: HomeActivity) {
        if (startedHome?.get() === activity) startedHome = null
    }

    override fun openHome(context: Context, reason: CarLinkHost.OpenReason) {
        runCatching {
            val home = if (reason == CarLinkHost.OpenReason.USB_ATTACHED) HomeActivity.usbStartupIntent(context)
                else HomeActivity.intent(context)
            context.startActivity(home
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    override fun openProjection(context: Context) {
        if (!CarPlayHostIntegration.hasSession()) return
        // A USB dialog/router can pause Home without hiding it. Arm only its HOME route;
        // finishing the dialog must resume that Activity before it can open projection.
        startedHome?.get()?.armUsbStartupProjection()
    }

    internal fun returnToExistingProjection(context: Context) {
        if (!CarPlayHostIntegration.hasSession()) return
        runCatching {
            context.startActivity(Intent(context, CarPlayHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { android.util.Log.w("CarLink", "Could not return to the connected projection", it) }
    }
}
