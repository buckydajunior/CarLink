package com.carlink.core.entry

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.SystemClock
import com.carlink.core.connection.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real watcher/UsbManager/SessionManager integration, without a device or a vendor session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class UsbStartupRecoveryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private lateinit var watcher: UsbWatcher
    private lateinit var session: SessionManager
    private var starts = 0
    private var stops = 0
    private var engineRunning = false
    private var events: ((AdapterEvent) -> Unit)? = null
    private var connectOnStart = true
    private var holdStops = false
    private val stopCompletions = mutableListOf<() -> Unit>()
    private val startedNodes = mutableListOf<String?>()

    @Before fun setup() {
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
        session = SessionManager(
            adapter = object : EngineAdapter {
                override fun hasSession() = engineRunning
                override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
                    check(!engineRunning)
                    starts++
                    startedNodes += plan.usbAttach?.let { UsbRouterActivity.usbDevice(it)?.deviceName }
                    engineRunning = true
                    events = listener
                    if (connectOnStart) listener(AdapterEvent.Connected)
                }
                override fun stop(onStopped: () -> Unit) {
                    stops++
                    val finish = { engineRunning = false; onStopped() }
                    if (holdStops) stopCompletions += finish else finish()
                }
            },
            environment = object : SessionManager.Environment {
                override fun preflight(request: SessionRequest) = emptyList<Problem>()
                override fun usbPhonePresent() = usb.deviceList.values.any { it.vendorId == 0x05AC }
                override fun currentUsbAttach(previous: Intent?) = UsbWatcher.get(app).currentIphoneAttach(previous)
                override fun returnHome() = Unit
                override fun log(message: String) = Unit
            },
            applySettings = {}, worker = { it.run() },
        )
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", session)
        watcher = UsbWatcher.get(app)
    }

    @After fun cleanup() {
        watcher.stop()
        session.stop()
        stopCompletions.toList().forEach { it() }
        stopCompletions.clear()
        idle()
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", null)
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
    }

    private fun phone(node: String = "/dev/bus/usb/001/002", vendor: Int = 0x05AC, name: String = "iPhone") =
        mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(vendor)
            `when`(it.productId).thenReturn(0x12A8)
            `when`(it.productName).thenReturn(name)
            `when`(it.deviceName).thenReturn(node)
        }
    private fun listed(device: UsbDevice) = shadowOf(usb).addOrUpdateUsbDevice(device, true)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun anAlreadyListedPhoneStartsImmediatelyWithoutASettlingDelay() {
        listed(phone())
        watcher.start(); idle()
        assertEquals(1, starts)
        assertEquals(SessionState.Phase.Connected, (session.state.value as SessionState.Active).phase)
    }

    @Test fun phoneEnumeratedAfterBootIsFoundWithoutAnAttachBroadcast() {
        watcher.onWake(); advance(1_600)
        assertEquals(0, starts)
        listed(phone()) // No ACTION_USB_DEVICE_ATTACHED: emulate the Linkswell missing the boot event.
        advance(1_500)
        assertEquals(1, starts)
        advance(60_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun aWakeRecoversTheSameHandledNodeAfterItsPriorSessionEnded() {
        listed(phone())
        watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        assertEquals(SessionState.Idle, session.state.value)
        watcher.onWake(); idle()
        assertEquals(2, starts)
    }

    @Test fun aRealWakeAfterDeepSleepIsNotMistakenForADuplicateBroadcast() {
        listed(phone())
        watcher.onWake(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        val uptime = SystemClock.uptimeMillis()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(20))
        assertEquals(uptime, SystemClock.uptimeMillis())
        watcher.onWake(); idle()
        assertEquals(2, starts)
    }

    @Test fun aNewWakeGetsItsOwnEnumerationWindowAfterSleepingMidScan() {
        watcher.onWake(); advance(20_000)
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(20))
        watcher.onWake(); advance(15_100)
        listed(phone()) // Later than the previous wake's remaining ten-second window.
        advance(5_000)
        assertEquals(1, starts)
    }

    @Test fun openingHomeRetriesAnAlreadyHandledFailedStartupWithoutReplugging() {
        listed(phone())
        watcher.start(); idle()
        events!!.invoke(AdapterEvent.Failed(FailureReason.USB, "USB not ready during boot")); idle()
        assertTrue(session.state.value is SessionState.Failed)
        watcher.onHomeShown(); idle()
        assertEquals(2, starts)
        assertEquals(SessionState.Phase.Connected, (session.state.value as SessionState.Active).phase)
    }

    @Test fun healthyProjectionSurvivesDuplicateBootWakeAndHomeScans() {
        listed(phone())
        watcher.start(); idle()
        repeat(3) { watcher.onWake(); watcher.onHomeShown(); watcher.start(); idle() }
        advance(35_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun manualDisconnectIsNotUndoneByWakeOrHomeEvenAfterTheOldGraceExpires() {
        listed(phone())
        watcher.start(); idle()
        session.disconnect(); idle(); advance(11_000)
        watcher.onWake(); watcher.onHomeShown(); idle()
        assertEquals(1, starts)
        assertEquals(SessionState.Idle, session.state.value)
        session.connectUsb(); idle()
        assertEquals(2, starts)
    }

    @Test fun pausedPhoneTemporarilyMissingDuringWakeDoesNotResumeOnAnotherNode() {
        val original = phone()
        listed(original)
        watcher.start(); idle()
        session.disconnect(); idle()
        shadowOf(usb).removeUsbDevice(original)
        watcher.onWake(); advance(11_000)
        listed(phone(node = "/dev/bus/usb/001/009"))
        advance(10_000)
        assertEquals(1, starts)
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test fun scanningStopsAfterThirtySecondsRatherThanPollingForever() {
        watcher.onWake(); advance(31_000)
        listed(phone())
        advance(60_000)
        assertEquals(0, starts)
        watcher.onWake(); idle()
        assertEquals(1, starts)
    }

    @Test fun aPausedScanDoesNotConsumeALaterRealAttachForTheNewNode() {
        val original = phone()
        listed(original)
        watcher.start(); idle()
        session.disconnect(); idle()
        shadowOf(usb).removeUsbDevice(original)
        watcher.onWake(); advance(11_000)
        val replacement = phone(node = "/dev/bus/usb/001/009")
        listed(replacement); advance(10_000)
        assertEquals(1, starts) // The scan itself respects Disconnect.
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, replacement))
        idle()
        assertEquals(2, starts)
    }

    @Test fun stoppingTheWatcherCancelsItsDelayedScans() {
        watcher.start(); watcher.stop()
        listed(phone()); advance(31_000)
        assertEquals(0, starts)
        watcher.start(); idle()
        assertEquals(1, starts)
    }

    @Test fun unrelatedDevicesDoNotEndTheStartupSearch() {
        listed(phone(vendor = 0x0781, name = "USB drive"))
        watcher.onWake(); advance(500)
        assertEquals(0, starts)
        listed(phone()); advance(1_000)
        assertEquals(1, starts)
    }

    private fun detach(device: UsbDevice) {
        shadowOf(usb).removeUsbDevice(device)
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device))
        idle()
    }

    private fun attach(device: UsbDevice) {
        listed(device)
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device))
        idle()
    }

    @Test fun replugAfterAutomaticEndAndConfirmedDepartureDoesNotStaySuppressed() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle() // Transport ends before USB removal is delivered.
        detach(original); advance(1_500)
        attach(phone(node = "/dev/bus/usb/001/009"))
        assertEquals(2, starts)
    }

    @Test fun aQuickAttachDeclinedDuringAutomaticStopIsRetriedWithoutAnotherBroadcast() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        advance(11_000)
        assertEquals(2, starts)
    }

    @Test fun aFailedSessionAlsoRearmsAfterARealDeparture() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Failed(FailureReason.USB, "link closed")); idle()
        detach(original); advance(1_500)
        attach(phone(node = "/dev/bus/usb/001/009"))
        assertEquals(2, starts)
    }

    @Test fun detachStartsABoundedSearchForAMissingReattachBroadcast() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(2_000)
        listed(phone(node = "/dev/bus/usb/001/009"))
        advance(1_100)
        assertEquals(2, starts)
    }

    @Test fun rapidConnectedReplugRestartsOnceAndWaitsForTheOldTeardown() {
        val original = phone()
        listed(original); watcher.start(); idle()
        holdStops = true
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        advance(1_200)
        assertEquals(1, starts)
        assertEquals(1, stops)
        holdStops = false
        stopCompletions.toList().forEach { it() }; stopCompletions.clear(); idle()
        assertEquals(2, starts)
        assertEquals("/dev/bus/usb/001/009", startedNodes.last())
    }

    @Test fun engineOwnedRetryWinsTheRapidReplugGrace() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        events!!.invoke(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        advance(1_500)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun lateControlEndKeepsTheAlreadyReturnedPhoneForOneRecovery() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        advance(10_500)
        assertEquals(2, starts)
        assertEquals("/dev/bus/usb/001/009", startedNodes.last())
    }

    @Test fun aDeferredRecoveryRefreshesItsIntentAfterAnotherUsbEnumeration() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        detach(original); advance(100)
        val returned = phone(node = "/dev/bus/usb/001/009")
        attach(returned)
        shadowOf(usb).removeUsbDevice(returned)
        listed(phone(node = "/dev/bus/usb/001/010")) // No further broadcast.
        advance(10_500)
        assertEquals(2, starts)
        assertEquals("/dev/bus/usb/001/010", startedNodes.last())
    }

    @Test fun repeatedFailureReenumerationCannotCreateAnAutomaticRecoveryLoop() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        detach(original); advance(100)
        val returned = phone(node = "/dev/bus/usb/001/009")
        attach(returned)
        connectOnStart = false
        advance(10_500)
        assertEquals(2, starts)
        events!!.invoke(AdapterEvent.Failed(FailureReason.USB, "still unavailable")); idle()
        detach(returned); advance(100)
        attach(phone(node = "/dev/bus/usb/001/010"))
        advance(15_000)
        assertEquals(2, starts)
    }

    @Test fun manualDisconnectCancelsAnAlreadyPendingAutomaticReplug() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        session.disconnect(); idle(); advance(15_000)
        watcher.onHomeShown(); watcher.onWake(); idle()
        assertEquals(1, starts)
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test fun aNewExplicitConnectMakesTheOldDeferredCallbackHarmless() {
        val original = phone()
        listed(original); watcher.start(); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        detach(original); advance(100)
        val returned = phone(node = "/dev/bus/usb/001/009")
        attach(returned)
        session.connectUsb(attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, returned)); idle()
        advance(15_000)
        assertEquals(2, starts)
    }

    @Test fun connectedSnapshotUsesTheNcmNodeRatherThanTheBootstrapIntent() {
        val original = phone()
        connectOnStart = false
        listed(original); watcher.start(); idle()
        shadowOf(usb).removeUsbDevice(original)
        val ncm = phone(node = "/dev/bus/usb/001/004")
        listed(ncm)
        events!!.invoke(AdapterEvent.Connected); idle()
        connectOnStart = true
        detach(ncm); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        advance(1_200)
        assertEquals(2, starts)
    }

    @Test fun staleOldNodeDoesNotEndTheMissingAttachSearch() {
        val original = phone()
        listed(original); watcher.start(); idle()
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, original))
        idle(); advance(600) // Emulate an OEM list that still contains the just-removed node.
        shadowOf(usb).removeUsbDevice(original)
        listed(phone(node = "/dev/bus/usb/001/009"))
        advance(1_000)
        assertEquals(2, starts)
    }

    @Test fun aNonPhoneDetachDoesNotStartPhoneRecoveryOrStopProjection() {
        listed(phone()); watcher.start(); idle()
        val drive = phone(node = "/dev/bus/usb/001/020", vendor = 0x0781, name = "USB drive")
        listed(drive); detach(drive); advance(5_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun engineRetryThenTerminalEndRetainsTheReturnedPhoneAfterTheGrace() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        events!!.invoke(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        advance(2_000)
        assertEquals(1, starts)
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        advance(10_500)
        assertEquals(2, starts)
    }

    @Test fun successfulEngineRecoveryClearsPriorReturnedPhoneEvidence() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/009"))
        events!!.invoke(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        advance(1_500)
        events!!.invoke(AdapterEvent.Connected); idle()
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        advance(15_000)
        assertEquals(1, starts) // No fresh detach/attach after that successful connection.
    }

    @Test fun theEngineKeepsItsInternalStartupUsbReenumeration() {
        val original = phone()
        connectOnStart = false
        listed(original); watcher.start(); idle()
        detach(original); advance(100)
        attach(phone(node = "/dev/bus/usb/001/004"))
        advance(4_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun aChangedNodeWithoutADetachRecordDoesNotResetAHealthySession() {
        val original = phone()
        listed(original); watcher.start(); idle()
        shadowOf(usb).removeUsbDevice(original)
        listed(phone(node = "/dev/bus/usb/001/009"))
        watcher.onHomeShown(); idle(); advance(2_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun detachEnumerationSearchAlsoStopsAfterThirtySeconds() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(31_000)
        listed(phone(node = "/dev/bus/usb/001/009"))
        advance(60_000)
        assertEquals(1, starts)
    }

    @Test fun engineRetryRetainsDepartureWhenReplacementArrivesAfterTheGrace() {
        val original = phone()
        listed(original); watcher.start(); idle()
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, original))
        idle()
        events!!.invoke(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        advance(1_500) // The OEM still reports the stale old entry throughout the grace.
        shadowOf(usb).removeUsbDevice(original)
        attach(phone(node = "/dev/bus/usb/001/009"))
        events!!.invoke(AdapterEvent.Ended(false)); idle()
        advance(10_500)
        assertEquals(2, starts)
    }

    @Test fun aReusedUsbNodeIsFoundAfterItsAbsenceWasObservedWithoutAnAttachBroadcast() {
        val original = phone()
        listed(original); watcher.start(); idle()
        detach(original); advance(2_000)
        listed(phone()) // Same path, but a genuinely new enumeration after observed absence.
        advance(1_100)
        assertEquals(2, starts)
    }
}
