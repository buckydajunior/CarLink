package com.carlink.core.entry

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.carlink.core.connection.AdapterEvent
import com.carlink.core.connection.CarLinkConfig
import com.carlink.core.connection.CarLinkCore
import com.carlink.core.connection.CarLinkHost
import com.carlink.core.connection.Connection
import com.carlink.core.connection.EngineAdapter
import com.carlink.core.connection.Problem
import com.carlink.core.connection.SessionManager
import com.carlink.core.connection.SessionPlan
import com.carlink.core.connection.SessionRequest
import com.carlink.core.connection.SessionState
import com.carlink.core.core.PhoneKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the real USB Activity, including the cold-start watcher/Activity race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class UsbRouterActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<Pair<Context, CarLinkHost.OpenReason>>()
    private var preflights = 0
    private var engineHostExists = false
    private var engineStarts = 0
    private var blockPreflight = true
    private var deferSettings = false
    private val settingsWork = mutableListOf<Runnable>()
    private lateinit var session: SessionManager

    @Before fun installTestHostAndBlockedSession() {
        val host = object : CarLinkHost {
            override fun openHome(context: Context, reason: CarLinkHost.OpenReason) { opened += context to reason }
        }
        // Install only the collaborators under test, avoiding engine/native startup in this router test.
        ReflectionHelpers.setStaticField(CarLinkCore::class.java, "installed", CarLinkConfig("Test receiver", host))
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
        session = SessionManager(
            adapter = object : EngineAdapter {
                override fun hasSession() = engineHostExists
                override fun stop(onStopped: () -> Unit) { engineHostExists = false; onStopped() }
                override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
                    engineStarts++
                    engineHostExists = true
                }
            },
            environment = object : SessionManager.Environment {
                override fun preflight(request: SessionRequest): List<Problem> {
                    preflights++
                    return if (blockPreflight) listOf(Problem.PERMISSIONS) else emptyList()
                }
                override fun usbPhonePresent() = true
                override fun returnHome() = Unit
                override fun log(message: String) = Unit
            },
            applySettings = {},
            worker = { if (deferSettings) settingsWork += it else it.run() },
        )
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", session)
    }

    @After fun clearSingletons() {
        session.stop()
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", null)
        ReflectionHelpers.setStaticField(CarLinkCore::class.java, "installed", null)
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
    }

    private fun device(vendor: Int = 0x05AC, product: Int = 0x12A8, name: String = "iPhone"): UsbDevice =
        mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(vendor)
            `when`(it.productId).thenReturn(product)
            `when`(it.productName).thenReturn(name)
            `when`(it.deviceName).thenReturn("/dev/bus/usb/001/002")
        }

    private fun route(device: UsbDevice): UsbRouterActivity = Robolectric.buildActivity(UsbRouterActivity::class.java,
        Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)).create().get()

    @Test fun firstIphoneAttachShowsHostEvenWhenPermissionsBlockTheSession() {
        val activity = route(device())
        assertEquals(1, opened.size)
        assertSame(activity, opened.single().first)
        assertEquals(CarLinkHost.OpenReason.USB_ATTACHED, opened.single().second)
        assertTrue(activity.isFinishing)
        assertTrue(session.state.value is SessionState.Blocked)
        assertEquals(1, preflights)
    }

    @Test fun watcherPreclaimedAttachStillShowsHostWithoutStartingTheSessionTwice() {
        val phone = device()
        assertTrue(UsbWatcher.get(app).claim(phone))
        session.onUsbPhone(PhoneKind.IPHONE, "iPhone", Intent())
        val blocked = session.state.value
        val activity = route(phone)
        assertEquals(listOf(activity to CarLinkHost.OpenReason.USB_ATTACHED), opened)
        assertEquals(1, preflights)
        assertSame(blocked, session.state.value)
    }

    @Test fun internalReenumerationDoesNotCoverAnExistingCarPlayHost() {
        blockPreflight = false
        session.connectUsb()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.state.value is SessionState.Active)
        assertEquals(1, engineStarts)
        val phone = device()
        route(phone)
        assertTrue(opened.isEmpty())
        assertEquals(1, preflights)
        assertEquals(1, engineStarts)
    }

    @Test fun preclaimedAttemptWaitingForItsFirstWindowStillShowsHost() {
        blockPreflight = false
        deferSettings = true
        val phone = device()
        assertTrue(UsbWatcher.get(app).claim(phone))
        session.onUsbPhone(PhoneKind.IPHONE, "iPhone", Intent())
        assertTrue(session.state.value is SessionState.Active)
        assertEquals(0, engineStarts)
        val activity = route(phone)
        assertEquals(listOf(activity to CarLinkHost.OpenReason.USB_ATTACHED), opened)
        assertEquals(1, preflights)
        assertEquals(1, settingsWork.size)
    }

    @Test fun androidDevicesAndIpadsRemainSilent() {
        route(device(vendor = 0x18D1, name = "Pixel"))
        route(device(product = 0x12AB, name = "iPad Pro"))
        assertTrue(opened.isEmpty())
        assertEquals(0, preflights)
        assertEquals(SessionState.Idle, session.state.value)
    }
}
