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
@Config(sdk = [29], application = Application::class)
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

    @Before fun setup() {
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
        session = SessionManager(
            adapter = object : EngineAdapter {
                override fun hasSession() = engineRunning
                override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
                    check(!engineRunning)
                    starts++
                    engineRunning = true
                    events = listener
                    listener(AdapterEvent.Connected)
                }
                override fun stop(onStopped: () -> Unit) {
                    stops++
                    engineRunning = false
                    onStopped()
                }
            },
            environment = object : SessionManager.Environment {
                override fun preflight(request: SessionRequest) = emptyList<Problem>()
                override fun usbPhonePresent() = usb.deviceList.values.any { it.vendorId == 0x05AC }
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
}
