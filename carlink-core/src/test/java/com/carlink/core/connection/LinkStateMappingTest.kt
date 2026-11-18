package com.carlink.core.connection
import com.carlink.core.core.*
import org.junit.Assert.*
import org.junit.Test
class LinkStateMappingTest {
    private val request = SessionRequest("iPhone")
    @Test fun eachPreflightErrorMapsToItsActionableUiProblem() {
        assertEquals(LinkProblem.PERMISSIONS, SessionState.Blocked(request, listOf(Problem.PERMISSIONS)).toLinkState().problem)
        assertEquals(LinkProblem.VPN_CONSENT, SessionState.Blocked(request, listOf(Problem.VPN_CONSENT)).toLinkState().problem)
        assertEquals(LinkProblem.CARPLAY_AUTH_MISSING, SessionState.Blocked(request, listOf(Problem.CARPLAY_AUTH_MISSING)).toLinkState().problem)
    }
    @Test fun usbSlowAndRecoveringNoticesSurviveMapping() {
        val active = SessionState.Active(request, 1, SessionState.Phase.Starting,
            Notice(Notice.Kind.SLOW, ProgressStage.HANDSHAKE, "Slow handshake"))
        assertEquals(LinkNoticeKind.SLOW, active.toLinkState().notice?.kind)
        assertEquals(LinkRecovery.RETRY, active.copy(notice = Notice(Notice.Kind.RECOVERING, ProgressStage.HANDSHAKE,
            "Retrying", RecoveryAction.RETRY_STEP)).toLinkState().notice?.recovery)
        assertEquals(Transport.USB, active.toLinkState().transport)
    }
    @Test fun latePictureIsOfferedAsNoPictureOnlyWhenDirectOutputIsOff() {
        val active = SessionState.Active(request, 1, SessionState.Phase.Starting,
            Notice(Notice.Kind.SLOW, ProgressStage.STARTING_PROJECTION, "No frame"))
        assertEquals(LinkNoticeKind.NO_PICTURE, active.toLinkState(false).notice?.kind)
        assertEquals(LinkNoticeKind.SLOW, active.toLinkState(true).notice?.kind)
    }
}
