package com.carlink.core.connection

import com.carlink.core.core.Engine
import com.carlink.core.core.Transport

/** Fixed product capabilities, enforced before a trigger can inspect or start a phone. */
enum class ConnectionPolicy(val wireless: Boolean, val androidAuto: Boolean) {
    ALL(wireless = true, androidAuto = true),

    /** A dedicated wired iPhone receiver: plugging in is its only connection action. */
    USB_CARPLAY_ONLY(wireless = false, androidAuto = false),
    ;

    val alwaysConnectUsb: Boolean get() = this == USB_CARPLAY_ONLY

    fun allows(engine: Engine, transport: Transport): Boolean =
        (androidAuto || engine == Engine.CARPLAY) && (wireless || transport == Transport.USB)
}
