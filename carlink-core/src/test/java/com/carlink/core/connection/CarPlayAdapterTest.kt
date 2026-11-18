package com.carlink.core.connection
import android.app.Application
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlayAdapterTest {
    private class Hooks : CarPlayEngineHooks {
        var session = false; var wired = false; var openError: Throwable? = null
        val opened = mutableListOf<Intent?>(); val stops = mutableListOf<() -> Unit>()
        val statuses = mutableListOf<(CarPlayStatus?) -> Unit>(); val hosts = mutableListOf<(HostState) -> Unit>()
        override fun hasSession() = session
        override fun stopSession(completion: () -> Unit) { if (session) stops += completion else completion() }
        override fun addStatusListener(listener: (CarPlayStatus?) -> Unit) { statuses += listener }
        override fun removeStatusListener(listener: (CarPlayStatus?) -> Unit) { statuses -= listener }
        override fun addHostStateListener(listener: (HostState) -> Unit) { hosts += listener }
        override fun removeHostStateListener(listener: (HostState) -> Unit) { hosts -= listener }
        override fun setWired() { wired = true }
        override fun openHost(usbAttach: Intent?): Throwable? { opened += usbAttach; session = openError == null; return openError }
        fun finishStop() { session = false; stops.toList().also { stops.clear() }.forEach { it() } }
        fun status(status: CarPlayStatus?) = statuses.toList().forEach { it(status) }
        fun host(state: HostState) = hosts.toList().forEach { it(state) }
    }
    private val hooks = Hooks(); private val adapter = CarPlayAdapter(hooks)
    private val events = mutableListOf<AdapterEvent>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun start(attach: Intent? = null) { adapter.start(SessionPlan("iPhone", attach)) { events += it }; idle() }
    @Test fun startForcesWiredAndPassesTheExactUsbDeviceIntent() {
        val attach = Intent("usb"); start(attach)
        assertTrue(hooks.wired); assertEquals(listOf(attach), hooks.opened)
        assertEquals(AdapterEvent.Progress(1, 3, ProgressStage.WAITING_FOR_PHONE), events.first())
    }
    @Test fun aNullStatusDuringInternalRestartDoesNotEndTheSession() {
        start(); hooks.status(CarPlayStatus.OpeningDataPaths); hooks.status(null); idle()
        assertEquals(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE), events.last()); assertTrue(adapter.hasSession())
    }
    @Test fun onlyFirstFrameCountsAsConnectedAndDriverCloseEndsIt() {
        start(); hooks.status(CarPlayStatus.RunningControl); idle()
        assertFalse(events.any { it == AdapterEvent.Connected })
        hooks.host(HostState.FirstFrame); idle(); assertEquals(AdapterEvent.Connected, events.last())
        hooks.host(HostState.Closed); idle(); assertEquals(AdapterEvent.Ended(true), events.last())
    }
    @Test fun stopWaitsForTeardownAndDoesNotReportItsOwnClosedEvent() {
        start(); var stopped = false
        adapter.stop { stopped = true }; hooks.host(HostState.Closed); idle()
        assertFalse(stopped); assertFalse(events.any { it is AdapterEvent.Ended })
        hooks.finishStop(); idle(); assertTrue(stopped); assertTrue(hooks.statuses.isEmpty())
    }
    @Test fun aNewStartWaitsForAPreviousHostToFinishStopping() {
        hooks.session = true; start(); assertTrue(hooks.opened.isEmpty())
        hooks.finishStop(); idle(); assertEquals(1, hooks.opened.size)
    }
    @Test fun timeoutUnblocksTeardownOnlyOnce() {
        start(); var completions = 0; adapter.stop { completions++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_001)); assertEquals(1, completions)
        hooks.finishStop(); idle(); assertEquals(1, completions)
    }
    @Test fun hostLaunchFailureIsReportedAndListenersAreReleased() {
        hooks.openError = IllegalStateException("no activity"); start()
        assertEquals(AdapterEvent.Failed(FailureReason.ENGINE, "no activity"), events.last())
        assertTrue(hooks.hosts.isEmpty()); assertTrue(hooks.statuses.isEmpty())
    }
    @Test fun hardPermissionFailureStopsListeningRatherThanLoopingForever() {
        start(); hooks.host(HostState.PermissionsDenied(listOf("microphone"))); idle()
        assertTrue(events.last() is AdapterEvent.Failed); assertTrue(hooks.statuses.isEmpty())
    }
}
