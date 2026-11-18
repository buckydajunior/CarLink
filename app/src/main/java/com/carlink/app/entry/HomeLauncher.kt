package com.carlink.app.entry

import android.content.Context
import android.content.Intent
import com.carlink.app.ui.HomeActivity
import com.carlink.core.connection.CarLinkHost

/** Returns from an unplugged projection to the receiver's USB-ready screen. */
object HomeLauncher : CarLinkHost {
    override fun openHome(context: Context, reason: CarLinkHost.OpenReason) {
        runCatching {
            context.startActivity(HomeActivity.intent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
