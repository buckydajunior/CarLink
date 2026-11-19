package com.carlink.core.connection

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.carlink.core.core.PhoneKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

/** The retained wired lifecycle regressions: authorization, re-enumeration, teardown and bounded stalls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SessionManagerTest {
    private class FakeAdapter : EngineAdapter {
        val plans = mutableListOf<SessionPlan>()
        var listener: ((AdapterEvent) -> Unit)? = null
        var session = false
        var immediateStop = true
        var stopCount = 0
        val stops = mutableListOf<() -> Unit>()
        override fun hasSession() = session
        override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
            check(!session)
            plans += plan; this.listener = listener; session = true
        }
        override fun stop(onStopped: () -> Unit) {
            stopCount++
            val done = { session = false; onStopped() }
            if (immediateStop) done() else stops += done
        }
        fun finishStop() { stops.toList().also { stops.clear() }.forEach { it() } }
        fun emit(event: AdapterEvent) = listener!!.invoke(event)
    }
    private val adapter = FakeAdapter()
    private var problems = emptyList<Problem>()
    private var usbPresent = true
    private var returnHomes = 0
    private var serialOf: (Intent?) -> String? = { null }
    private val notes = mutableListOf<String>()
    private val env = object : SessionManager.Environment {
        override fun preflight(request: SessionRequest) = problems
        override fun usbPhonePresent() = usbPresent
        override fun usbSerial(attach: Intent?) = serialOf(attach)
        override fun returnHome() { returnHomes++ }
        override fun log(message: String) { notes += message }
    }
    private fun manager(worker: Executor = Executor { it.run() }) = SessionManager(adapter, env, {}, worker)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun plug(m: SessionManager, attach: Intent? = Intent()) { m.onUsbPhone(PhoneKind.IPHONE, "iPhone", attach); idle() }

    @Test fun aCableStartsCarPlayWithoutAConnectTapAndKeepsTheAttachIntent() {
        val m = manager(); val attach = Intent("usb")
        plug(m, attach)
        assertEquals(attach, adapter.plans.single().usbAttach)
        assertTrue(m.state.value is SessionState.Active)
    }
    @Test fun nonIphoneUsbDevicesNeverStart() {
        val m = manager(); m.onUsbPhone(PhoneKind.UNKNOWN, null, Intent()); idle()
        assertEquals(SessionState.Idle, m.state.value); assertTrue(adapter.plans.isEmpty())
    }
    @Test fun failedPreflightDoesNotOpenAnEngineAndRetryRechecksIt() {
        problems = listOf(Problem.PERMISSIONS)
        val m = manager(); plug(m)
        assertTrue(m.state.value is SessionState.Blocked); assertTrue(adapter.plans.isEmpty())
        problems = emptyList(); m.retry(); idle()
        assertEquals(1, adapter.plans.size)
    }
    @Test fun usbReenumerationDoesNotRestartTheActiveAttempt() {
        val m = manager(); plug(m)
        m.onUsbDetached(); advance(1000); plug(m)
        advance(SessionManager.USB_GONE_GRACE_MILLIS)
        assertEquals(1, adapter.plans.size); assertTrue(m.state.value.isActive)
    }
    @Test fun anotherSerialOnTheCableWaitsForTheOldTeardown() {
        serialOf = { it?.getStringExtra("serial") }
        val m = manager(); plug(m, Intent().putExtra("serial", "first"))
        adapter.immediateStop = false
        plug(m, Intent().putExtra("serial", "second"))
        assertEquals(1, adapter.plans.size)
        adapter.finishStop(); idle()
        assertEquals(2, adapter.plans.size)
        assertEquals("second", adapter.plans.last().usbAttach?.getStringExtra("serial"))
    }
    @Test fun retryCannotOpenOverAnUnfinishedTeardown() {
        val m = manager(); plug(m); adapter.immediateStop = false
        m.retry(); idle()
        assertEquals(1, adapter.plans.size)
        adapter.finishStop(); idle(); assertEquals(2, adapter.plans.size)
    }
    @Test fun disconnectSuppressesItsOwnReenumerationButAllowsADeliberateConnect() {
        val m = manager(); plug(m); m.disconnect(); idle(); plug(m)
        assertEquals(1, adapter.plans.size); assertEquals(SessionState.Idle, m.state.value)
        m.connectUsb(); idle(); assertEquals(2, adapter.plans.size)
    }
    @Test fun aRealReplugAfterDisconnectWithTheCableAlreadyOutStartsImmediately() {
        val m = manager(); plug(m); usbPresent = false; m.disconnect(); idle()
        usbPresent = true; plug(m); assertEquals(2, adapter.plans.size)
    }
    @Test fun anUnplugDuringBringupWaitsForReenumerationThenReturnsHome() {
        val m = manager(); plug(m); usbPresent = false; m.onUsbDetached()
        advance(SessionManager.USB_GONE_GRACE_MILLIS - 1); assertTrue(m.state.value.isActive)
        advance(2); assertEquals(SessionState.Idle, m.state.value); assertEquals(1, returnHomes)
    }
    @Test fun anEstablishedSessionUsesTheShortUnplugGraceEvenWhileRetrying() {
        val m = manager(); plug(m); adapter.emit(AdapterEvent.Connected); idle()
        adapter.emit(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        usbPresent = false; m.onUsbDetached(); advance(SessionManager.USB_GONE_CONNECTED_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
    }
    @Test fun unplugClearsTheOldBlockedError() {
        problems = listOf(Problem.VPN_CONSENT)
        val m = manager(); plug(m); usbPresent = false; m.onUsbDetached()
        advance(SessionManager.USB_GONE_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
    }
    @Test fun aFailureDoesNotAutorestartWhenStoppingReenumeratesThePhone() {
        val m = manager(); plug(m); adapter.emit(AdapterEvent.Failed(FailureReason.USB, "unplug")); idle(); plug(m)
        assertEquals(1, adapter.plans.size); assertTrue(m.state.value is SessionState.Failed)
    }
    @Test fun callbacksFromAnOldAttemptCannotEndTheNewOne() {
        val m = manager(); plug(m); val old = adapter.listener!!
        m.retry(); idle(); old(AdapterEvent.Failed(FailureReason.USB)); idle()
        assertTrue(m.state.value is SessionState.Active); assertEquals(2, adapter.plans.size)
    }
    @Test fun cancelledWorkerWorkCannotOpenProjectionLater() {
        val tasks = mutableListOf<Runnable>()
        val m = manager(Executor { tasks += it }); plug(m)
        m.disconnect(); idle(); tasks.forEach { it.run() }; idle()
        assertTrue(adapter.plans.isEmpty()); assertEquals(SessionState.Idle, m.state.value)
    }
    @Test fun slowPhoneIsExplainedAfterTenSeconds() {
        val m = manager(); plug(m); advance(10_000)
        assertEquals(Notice.Kind.PHONE_NOT_ANSWERING, (m.state.value as SessionState.Active).notice?.kind)
        assertEquals(1, adapter.plans.size)
    }
    @Test fun pictureStallRetriesOnceThenAsksForAReplug() {
        val m = manager(); plug(m)
        adapter.emit(AdapterEvent.Progress(3, 3, ProgressStage.STARTING_PROJECTION)); idle(); advance(20_000)
        assertEquals(2, adapter.plans.size)
        adapter.emit(AdapterEvent.Progress(3, 3, ProgressStage.STARTING_PROJECTION)); idle(); advance(20_000)
        assertTrue(m.state.value is SessionState.Failed)
        assertEquals(FailureReason.USB, (m.state.value as SessionState.Failed).reason)
    }
    @Test fun firstFrameRecordsSuccessAndCancelsTheStartupWatchdog() {
        val m = manager(); plug(m); advance(2000); adapter.emit(AdapterEvent.Connected); idle(); advance(120_000)
        assertEquals(SessionState.Phase.Connected, (m.state.value as SessionState.Active).phase)
        assertEquals(1, m.health.value.single().successes)
    }

    @Test fun readinessRecoveryWaitsForAnUnfinishedFailedSessionTeardown() {
        val m = manager(); plug(m); adapter.immediateStop = false
        adapter.emit(AdapterEvent.Failed(FailureReason.USB, "boot USB not ready")); idle()
        assertTrue(m.state.value is SessionState.Failed)
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertEquals(1, adapter.plans.size)
        adapter.finishStop(); idle()
        assertEquals(2, adapter.plans.size)
    }

    @Test fun readinessRecoveryKeepsAnActiveEngineRetryInsteadOfRestartingIt() {
        val m = manager(); plug(m)
        adapter.emit(AdapterEvent.Connected); idle()
        adapter.emit(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertEquals(1, adapter.plans.size)
        assertEquals(0, adapter.stopCount)
        assertTrue((m.state.value as SessionState.Active).phase is SessionState.Phase.Retrying)
    }

    @Test fun engineNotificationDisconnectAlsoPausesReadinessRecovery() {
        val m = manager(); plug(m)
        adapter.emit(AdapterEvent.Ended(true)); idle(); advance(11_000)
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertEquals(SessionState.Idle, m.state.value)
        assertEquals(1, adapter.plans.size)
        m.connectUsb(); idle()
        assertEquals(2, adapter.plans.size)
    }

    @Test fun aNonUserEndCanRecoverOnWakeWhileTheSameCableIsStillPresent() {
        val m = manager(); plug(m)
        adapter.emit(AdapterEvent.Ended(false)); idle()
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertEquals(2, adapter.plans.size)
    }

    @Test fun readinessRechecksBootPreflightWithoutBypassingRequiredConsent() {
        problems = listOf(Problem.VPN_CONSENT)
        val m = manager(); plug(m)
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertTrue(adapter.plans.isEmpty())
        problems = emptyList()
        m.onUsbReady(PhoneKind.IPHONE, "iPhone", Intent()); idle()
        assertEquals(1, adapter.plans.size)
    }
}
