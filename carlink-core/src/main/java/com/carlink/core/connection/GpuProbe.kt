package com.carlink.core.connection

import android.content.Context
import android.os.Build

/** Read an already learned renderer name without starting an EGL context beside USB startup. */
object GpuProbe {
    fun cached(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences("carlink_device", Context.MODE_PRIVATE)
        return if (prefs.getString("gl_renderer_fingerprint", null) == Build.FINGERPRINT)
            prefs.getString("gl_renderer", null)?.takeIf { it.isNotBlank() } else null
    }
}
