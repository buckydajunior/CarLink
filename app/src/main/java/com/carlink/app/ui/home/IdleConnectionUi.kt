package com.carlink.app.ui.home

import androidx.annotation.StringRes
import com.carlink.app.R
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState

enum class IdleAction(@param:StringRes val label: Int) {
    ALLOW(R.string.carlink_f150_allow), CONNECT(R.string.carlink_f150_connect),
    RETRY(R.string.carlink_f150_retry), OPEN(R.string.carlink_f150_open),
    DISCONNECT(R.string.carlink_f150_disconnect),
}

enum class IdleTone { WAITING, CONNECTING, CONNECTED, ATTENTION }

data class IdleConnectionUi(
    @param:StringRes val status: Int,
    @param:StringRes val message: Int,
    val tone: IdleTone,
    val action: IdleAction? = null,
    val canDisconnect: Boolean = false,
    val notice: String? = null,
) {
    companion object {
        fun from(link: LinkState, needsPermissions: Boolean = false, usbAttached: Boolean = false): IdleConnectionUi {
            val fixable = needsPermissions || link.problem == LinkProblem.PERMISSIONS || link.problem == LinkProblem.VPN_CONSENT
            val busy = link.stage == LinkStage.CONNECTING || link.stage == LinkStage.WAITING
            val connected = link.stage == LinkStage.CONNECTED
            val failed = link.stage == LinkStage.FAILED
            val status = when {
                needsPermissions || link.problem == LinkProblem.PERMISSIONS -> R.string.carlink_f150_access_title
                fixable || failed -> R.string.carlink_f150_failed
                connected -> R.string.carlink_f150_idle_connected
                link.stage == LinkStage.WAITING -> R.string.carlink_f150_idle_waiting
                busy && link.step >= 3 -> R.string.carlink_f150_idle_opening
                busy -> R.string.carlink_f150_idle_connecting
                else -> R.string.carlink_f150_idle_waiting
            }
            val message = when {
                needsPermissions || link.problem == LinkProblem.PERMISSIONS -> R.string.carlink_f150_access_body
                link.problem == LinkProblem.CARPLAY_AUTH_MISSING -> R.string.carlink_f150_auth_missing
                link.problem == LinkProblem.VPN_CONSENT -> R.string.carlink_f150_vpn_body
                connected -> R.string.carlink_f150_connected_body
                busy -> R.string.carlink_f150_connecting_body
                failed -> R.string.carlink_f150_failed_body
                else -> R.string.carlink_f150_ready_body
            }
            val action = when {
                fixable -> IdleAction.ALLOW
                connected -> IdleAction.OPEN
                failed && link.problem != LinkProblem.CARPLAY_AUTH_MISSING -> IdleAction.RETRY
                !busy && !failed && usbAttached -> IdleAction.CONNECT
                else -> null
            }
            return IdleConnectionUi(status, message, when {
                fixable || failed -> IdleTone.ATTENTION
                connected -> IdleTone.CONNECTED
                busy -> IdleTone.CONNECTING
                else -> IdleTone.WAITING
            }, action, busy || connected, link.detail?.takeIf { busy && it.isNotBlank() })
        }
    }
}
