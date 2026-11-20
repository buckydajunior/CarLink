package com.carlink.core.connection
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
class CarPlayEventMappingTest {
    @Test fun wiredStagesFollowRealPhoneProgress() {
        assertEquals(ProgressStage.WAITING_FOR_PHONE, CarPlayEventMapping.stageOf(CarPlayStatus.RequestingIphonePermission))
        assertEquals(ProgressStage.HANDSHAKE, CarPlayEventMapping.stageOf(CarPlayStatus.WaitingForReenumeration))
        assertEquals(ProgressStage.STARTING_PROJECTION, CarPlayEventMapping.stageOf(CarPlayStatus.AttachingNetwork))
    }
    @Test fun permissionAndAuthenticationErrorsKeepTheirMeaning() {
        assertEquals(CarPlayFailure(FailureReason.USB, true, "USB permission denied", null),
            CarPlayEventMapping.classify(CarPlayStatus.Failed("USB permission denied"), null))
        assertEquals(FailureReason.AUTHENTICATION, CarPlayEventMapping.classify(CarPlayStatus.Failed("MFi failed"), null).reason)
    }
    @Test fun progressNeverRunsBackwardWithinAnAttempt() {
        val t = CarPlaySessionTracker(); t.started()
        assertEquals(listOf(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE)), t.onStatus(CarPlayStatus.OpeningDataPaths))
        assertTrue(t.onStatus(CarPlayStatus.DiscoveringIphone).isEmpty())
        assertTrue(t.onStatus(null).isEmpty())
    }
    @Test fun controlLoopAloneDoesNotMeanTheProjectionHasAPicture() {
        val t = CarPlaySessionTracker(); t.started()
        assertTrue(t.onStatus(CarPlayStatus.RunningControl).none { it == AdapterEvent.Connected })
        assertEquals(listOf(AdapterEvent.Connected), t.onHostState(HostState.FirstFrame))
    }
    @Test fun unboundedUsbRetriesAreStillBoundedByTheOriginalBudget() {
        val t = CarPlaySessionTracker(); t.started()
        repeat(5) { assertTrue(t.onHostState(HostState.Retrying(it + 1, null, 2000, null, "lost" )).single() is AdapterEvent.Retrying) }
        assertTrue(t.onHostState(HostState.Retrying(6, null, 2000, null, "lost")).single() is AdapterEvent.Failed)
        assertTrue(t.finished); assertTrue(t.onHostState(HostState.FirstFrame).isEmpty())
    }
    @Test fun exhaustedUsbLossAfterProjectionEndsNormally() {
        val t = CarPlaySessionTracker(maxUnboundedRetries = 1); t.started(); t.onHostState(HostState.FirstFrame)
        t.onHostState(HostState.Retrying(1, null, 2000, null, "lost"))
        assertEquals(listOf(AdapterEvent.Ended(false)), t.onHostState(HostState.Retrying(2, null, 2000, null, "lost")))
    }
}
