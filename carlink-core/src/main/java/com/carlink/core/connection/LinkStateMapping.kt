package com.carlink.core.connection

import com.carlink.core.core.*

fun SessionState.toLinkState(directVideoOutput: Boolean = true): LinkState = when (this) {
    SessionState.Idle -> LinkState.Idle
    is SessionState.Blocked -> LinkState(LinkStage.FAILED, Engine.CARPLAY, Transport.USB, request.phoneName,
        problem = problems.first().toLinkProblem())
    is SessionState.Failed -> LinkState(LinkStage.FAILED, Engine.CARPLAY, Transport.USB, request.phoneName,
        stage?.let(::stepOf) ?: 0, if (stage != null) 3 else 0, detail, reason.toLinkProblem())
    is SessionState.Active -> {
        val state = when (val p = phase) {
            SessionState.Phase.Starting -> LinkState(LinkStage.WAITING, Engine.CARPLAY, Transport.USB, request.phoneName, 1, 3)
            is SessionState.Phase.Progress -> LinkState(if (p.stage == ProgressStage.WAITING_FOR_PHONE || p.stage == ProgressStage.PHONE_NOT_ANSWERING)
                LinkStage.WAITING else LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, request.phoneName, stepOf(p.stage), 3)
            is SessionState.Phase.Retrying -> LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, request.phoneName,
                stepOf(p.stage), 3, p.detail, notice = LinkNotice(LinkNoticeKind.RETRYING, reason = p.reason.toLinkProblem()))
            SessionState.Phase.Connected -> LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.USB, request.phoneName, 3, 3)
        }
        val mappedNotice = notice?.let { n ->
            if (!directVideoOutput && n.stage == ProgressStage.STARTING_PROJECTION) LinkNotice(LinkNoticeKind.NO_PICTURE)
            else when (n.kind) {
                Notice.Kind.PHONE_NOT_ANSWERING -> LinkNotice(LinkNoticeKind.NOT_ANSWERING)
                Notice.Kind.SLOW -> LinkNotice(LinkNoticeKind.SLOW)
                Notice.Kind.RECOVERING -> LinkNotice(LinkNoticeKind.RECOVERING, when (n.action) {
                    RecoveryAction.NUDGE -> LinkRecovery.WAKE_PHONE
                    RecoveryAction.RETRY_STEP -> LinkRecovery.RETRY
                    RecoveryAction.ASK_REPLUG -> LinkRecovery.REPLUG
                    RecoveryAction.GIVE_UP, null -> null
                })
            }
        }
        state.copy(detail = notice?.message ?: state.detail, notice = mappedNotice ?: state.notice)
    }
}
private fun stepOf(stage: ProgressStage): Int = when (stage) {
    ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> 1
    ProgressStage.HANDSHAKE -> 2
    ProgressStage.STARTING_PROJECTION -> 3
}
fun Problem.toLinkProblem(): LinkProblem = when (this) {
    Problem.PERMISSIONS -> LinkProblem.PERMISSIONS
    Problem.VPN_CONSENT -> LinkProblem.VPN_CONSENT
    Problem.CARPLAY_AUTH_MISSING -> LinkProblem.CARPLAY_AUTH_MISSING
}
fun FailureReason.toLinkProblem(): LinkProblem = when (this) {
    FailureReason.LINK_TOO_WEAK -> LinkProblem.LINK_TOO_WEAK
    FailureReason.PHONE_DID_NOT_ANSWER -> LinkProblem.PHONE_DID_NOT_ANSWER
    FailureReason.USB -> LinkProblem.USB
    FailureReason.BLOCKED -> LinkProblem.PERMISSIONS
    FailureReason.AUTHENTICATION -> LinkProblem.CARPLAY_AUTH_MISSING
    FailureReason.ENGINE -> LinkProblem.ENGINE
}
