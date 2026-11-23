package com.carlink.core.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-scan an already-connected cable after ignition wake; never starts a radio or opens Home. */
class WiredWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) {
            val app = context.applicationContext
            val action = intent.action
            AfterUserUnlock.run(app, WAKE_KEY) {
                com.carlink.core.diagnostics.EventLog.add("USB", "boot/ignition wake: $action")
                UsbWatcher.get(app).onWake()
            }
        }
    }
    companion object {
        private val WAKE_KEY = Any()
        internal val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.BOOT_IPO",
            "com.fyt.boot.ACCON",
            "com.glsx.boot.ACCON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            "com.cayboy.action.ACC_ON",
            "com.carboy.action.ACC_ON",
        )
    }
}
