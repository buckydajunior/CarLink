package com.carlink.core.core

/** The only projection engine and transport shipped by this receiver. */
enum class Engine { CARPLAY }
enum class Transport { USB }
enum class PhoneKind { IPHONE, UNKNOWN;
    val engine: Engine? get() = if (this == IPHONE) Engine.CARPLAY else null
}
enum class LinkStage { IDLE, WAITING, CONNECTING, CONNECTED, FAILED }
enum class LinkProblem { CARPLAY_AUTH_MISSING, PERMISSIONS, VPN_CONSENT, USB, PHONE_DID_NOT_ANSWER, LINK_TOO_WEAK, ENGINE }
data class LinkState(
    val stage: LinkStage = LinkStage.IDLE,
    val engine: Engine? = null,
    val transport: Transport? = null,
    val phoneName: String? = null,
    val step: Int = 0,
    val stepCount: Int = 0,
    val detail: String? = null,
    val problem: LinkProblem? = null,
    val notice: LinkNotice? = null,
) {
    val isActive: Boolean get() = stage != LinkStage.IDLE && stage != LinkStage.FAILED
    companion object { val Idle = LinkState() }
}
data class LinkNotice(val kind: LinkNoticeKind, val recovery: LinkRecovery? = null, val reason: LinkProblem? = null)
enum class LinkNoticeKind { NOT_ANSWERING, SLOW, RECOVERING, RETRYING, NO_PICTURE }
enum class LinkRecovery { WAKE_PHONE, RETRY, REPLUG }
