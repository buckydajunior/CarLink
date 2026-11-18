package com.carlink.core.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.carlink.core.core.Transport

/** Required runtime access for the USB receiver. USB access and VPN consent have their own dialogs. */
object DevicePreflight {
    fun requiredPermissions(transport: Transport = Transport.USB): List<String> = listOf(Manifest.permission.RECORD_AUDIO)
    fun missingPermissions(context: Context): List<String> = requiredPermissions().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
