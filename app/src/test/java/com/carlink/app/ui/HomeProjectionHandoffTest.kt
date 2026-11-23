package com.carlink.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.carlink.app.entry.HomeLauncher
import com.carlink.app.CarLinkApplication
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.core.connection.AdapterEvent
import com.carlink.core.connection.Connection
import com.carlink.core.connection.CarLinkCore
import com.carlink.core.connection.CarLinkHost
import com.carlink.core.connection.EngineAdapter
import com.carlink.core.connection.SessionManager
import com.carlink.core.connection.SessionPlan
import com.carlink.core.connection.SessionRequest
import com.carlink.core.entry.UsbWatcher
import com.carlink.core.entry.AfterUserUnlock
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy
import java.lang.reflect.Modifier

/** Real Home lifecycle/intent handling, with a fake connected engine and no USB hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeProjectionHandoffTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var session: SessionManager
    private lateinit var engineEvents: (AdapterEvent) -> Unit
    private var starts = 0
    private var engineRunning = false
    private val activities = mutableListOf<ActivityController<HomeActivity>>()
    private lateinit var hostRegistry: Any
    private lateinit var hostType: Class<*>
    private lateinit var projectionHost: Any

    @Before fun prepare() {
        // AndroidX caches its first Application across these Robolectric test instances.
        // A real process has one Application; each test gets a fresh one and must use its own.
        val factory = ViewModelProvider.AndroidViewModelFactory::class.java
        factory.declaredFields.single { it.type == factory && Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }.set(null, null)
        // Match the installed app's own signature grant used by pre-33 NOT_EXPORTED receivers.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        UiPreferences(app).renderingMode = RenderingMode.LITE
        session = SessionManager(
            adapter = object : EngineAdapter {
                override fun hasSession() = engineRunning
                override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
                    starts++
                    engineRunning = true
                    engineEvents = listener
                }
                override fun stop(onStopped: () -> Unit) { engineRunning = false; onStopped() }
            },
            environment = object : SessionManager.Environment {
                override fun preflight(request: SessionRequest) = emptyList<com.carlink.core.connection.Problem>()
                override fun usbPhonePresent() = true
                override fun returnHome() = Unit
                override fun log(message: String) = Unit
            },
            applySettings = {}, worker = { it.run() },
        )
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", session)
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
        val registry = Class.forName("com.shilapi.xcertplay.HostSessions")
        hostRegistry = registry.getField("INSTANCE").get(null)
        hostType = Class.forName("com.shilapi.xcertplay.HostSessions\$Host")
        projectionHost = Proxy.newProxyInstance(hostType.classLoader, arrayOf(hostType)) { _, method, _ ->
            when (method.name) {
                "getEnded" -> false
                "retryNow", "setInterfaceSizeLive" -> false
                else -> null
            }
        }
        registry.getDeclaredMethod("opened", hostType).invoke(hostRegistry, projectionHost)
        assertTrue(CarPlayHostIntegration.hasSession())
        session.connectUsb(); idle()
    }

    @After fun cleanup() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        UsbWatcher.get(app).stop()
        session.stop(); idle()
        hostRegistry.javaClass.getDeclaredMethod("closed", hostType).invoke(hostRegistry, projectionHost)
        ReflectionHelpers.setStaticField(Connection::class.java, "instance", null)
        ReflectionHelpers.setStaticField(UsbWatcher::class.java, "instance", null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun home(usbStartup: Boolean = true): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java,
            if (usbStartup) HomeActivity.usbStartupIntent(app) else HomeActivity.intent(app))
            .also(activities::add).create().start().visible()
    private fun vm(activity: HomeActivity) = ViewModelProvider(activity)[CarLinkViewModel::class.java].also {
        assertSame("The ViewModel must use this test's current Application", app, it.getApplication<Application>())
    }
    private fun readyHome(controller: ActivityController<HomeActivity>) {
        vm(controller.get()).onRouteShown(Route.HOME)
        controller.resume(); idle()
        assertEquals(Lifecycle.State.RESUMED, controller.get().lifecycle.currentState)
    }
    private fun connected() { engineEvents(AdapterEvent.Connected); idle() }
    private fun launches(): List<Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedActivity ?: break)
    }
    private fun projections() = launches().filter { it.component?.className == CarPlayHostActivity::class.java.name }

    @Test fun usbStartupWaitsForResumedHomeThenReordersOnceWithoutAnotherSession() {
        connected()
        val controller = home()
        vm(controller.get()).onRouteShown(Route.HOME); idle()
        assertTrue(projections().isEmpty())
        controller.resume(); idle()
        assertEquals(Lifecycle.State.RESUMED, controller.get().lifecycle.currentState)
        assertEquals(com.carlink.core.core.LinkStage.CONNECTED, vm(controller.get()).link.value.stage)
        assertFalse("The resumed handoff must consume its one-shot marker", vm(controller.get()).usbStartupPending)
        assertTrue("The existing projection host must remain available", CarPlayHostIntegration.hasSession())
        val opened = projections()
        assertEquals("Projection launch warnings: ${ShadowLog.getLogsForTag("CarLink")}", 1, opened.size)
        val intent = opened.single()
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertEquals(1, starts)
        controller.pause().resume(); idle()
        assertTrue(projections().isEmpty())
    }

    @Test fun aLaterConnectionOpensOnlyAfterTheBackgroundHomeResumes() {
        val controller = home()
        readyHome(controller)
        controller.pause().stop(); idle()
        connected()
        assertTrue(projections().isEmpty())
        controller.start().resume(); idle()
        assertEquals(1, projections().size)
        assertEquals(1, starts)
    }

    @Test fun ordinaryHomeHasNoAutomaticProjectionRequest() {
        connected()
        readyHome(home(usbStartup = false))
        assertTrue(projections().isEmpty())
    }

    @Test fun navigatingToAnySettingsPageCancelsThePendingStartupHandoff() {
        val controller = home()
        readyHome(controller)
        val model = vm(controller.get())
        for (route in listOf(Route.SETTINGS, Route.EQUALIZER, Route.CALL_AUDIO, Route.ABOUT)) {
            model.requestUsbStartupProjection()
            model.onRouteShown(route)
            assertFalse(model.usbStartupPending)
            model.onRouteShown(Route.HOME)
        }
        connected()
        assertTrue(projections().isEmpty())
    }

    @Test fun aCarTileSettingsIntentCancelsStartupEvenBeforeComposeNavigates() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        connected()
        controller.pause(); idle() // The USB router/dialog temporarily covers Home.
        controller.newIntent(HomeActivity.carSettingsIntent(app))
        HomeLauncher.openProjection(app) // Before a Compose frame can render Settings.
        assertFalse(vm(controller.get()).usbStartupPending)
        assertEquals(1L, vm(controller.get()).settingsRequests.value)
        controller.resume(); idle()
        assertTrue(projections().isEmpty())
    }

    @Test fun anOldHomeFrameCannotOverrideThePendingCarSettingsRoute() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        connected()
        controller.pause(); idle()
        controller.newIntent(HomeActivity.carSettingsIntent(app))
        val model = vm(controller.get())
        // Reproduce an old HOME SideEffect before the navigation LaunchedEffect runs.
        model.onRouteRendered(Route.HOME)
        HomeLauncher.openProjection(app)
        assertFalse(model.isHomeRouteVisible)
        assertFalse(model.usbStartupPending)
        assertTrue(projections().isEmpty())

        model.onRouteRendered(Route.SETTINGS)
        model.onRouteShown(Route.HOME) // A subsequent deliberate return to Home is allowed.
        HomeLauncher.openProjection(app)
        assertTrue("The guard ends after the requested Settings frame", model.usbStartupPending)
    }

    @Test fun exitingToTheLinkswellLauncherCancelsPendingStartup() {
        val controller = home()
        readyHome(controller)
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "exitToCarHome")
        assertTrue(launches().any { it.action == Intent.ACTION_MAIN && it.hasCategory(Intent.CATEGORY_HOME) })
        connected()
        assertTrue(projections().isEmpty())
    }

    @Test fun aUsbIntentDeliveredToAnExistingHomeArmsTheHandoff() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        controller.newIntent(HomeActivity.usbStartupIntent(app)); idle()
        connected()
        assertEquals(1, projections().size)
    }

    @Test fun lockedHomeDoesNotCreateItsViewModelAndDestroyedSetupCannotResume() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val controller = home()
        controller.resume(); idle()
        val lazyVm = ReflectionHelpers.getField<Lazy<CarLinkViewModel>>(controller.get(), "vm\$delegate")
        assertFalse(lazyVm.isInitialized())
        controller.pause().stop().destroy()
        activities.remove(controller)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED)); idle()
        assertFalse(lazyVm.isInitialized())
        assertTrue(projections().isEmpty())
    }

    @Test fun aLockedHomeConsumesTheLatestSettingsIntentOnlyAfterUnlock() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val controller = home()
        controller.resume()
        controller.newIntent(HomeActivity.carSettingsIntent(app)); idle()
        val lazyVm = ReflectionHelpers.getField<Lazy<CarLinkViewModel>>(controller.get(), "vm\$delegate")
        assertFalse(lazyVm.isInitialized())
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED)); idle()
        assertTrue(lazyVm.isInitialized())
        assertEquals(1L, vm(controller.get()).settingsRequests.value)
        connected()
        assertTrue(projections().isEmpty())
    }

    @Test fun returningToProjectionWithoutAnExistingHostDoesNotStartOne() {
        hostRegistry.javaClass.getDeclaredMethod("closed", hostType).invoke(hostRegistry, projectionHost)
        HomeLauncher.returnToExistingProjection(app)
        assertTrue(projections().isEmpty())
        assertEquals(1, starts)
    }

    @Test fun aRouterCallbackOnlyArmsThePausedHomeUntilItResumes() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        connected()
        controller.pause(); idle()
        HomeLauncher.openProjection(app); idle()
        assertTrue(vm(controller.get()).usbStartupPending)
        assertTrue(projections().isEmpty())
        controller.resume(); idle()
        assertEquals(1, projections().size)
        assertEquals(1, starts)
    }

    @Test fun aRouterCallbackCannotOverrideSettingsOrAStoppedHomeBehindAnotherApp() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        connected()
        val model = vm(controller.get())
        model.onRouteShown(Route.SETTINGS)
        HomeLauncher.openProjection(app); idle()
        assertFalse(model.usbStartupPending)
        model.onRouteShown(Route.HOME)
        controller.pause().stop(); idle()
        HomeLauncher.openProjection(app); idle()
        assertFalse(model.usbStartupPending)
        controller.start().resume(); idle()
        assertTrue(projections().isEmpty())
    }

    @Test fun exitingHomeRejectsRouterCallbacksEvenBeforeAndroidDeliversOnStop() {
        val controller = home(usbStartup = false)
        readyHome(controller)
        connected()
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "exitToCarHome")
        launches()
        HomeLauncher.openProjection(app); idle()
        assertFalse(vm(controller.get()).usbStartupPending)
        assertTrue(projections().isEmpty())
    }

    @Test fun recreationDoesNotRearmAConsumedOriginalIntentButANewUsbIntentStillWorks() {
        connected()
        val first = home()
        readyHome(first)
        assertEquals(1, projections().size)
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        activities.remove(first)
        val restored = Robolectric.buildActivity(HomeActivity::class.java, HomeActivity.usbStartupIntent(app))
            .also(activities::add).create(state).start().visible()
        readyHome(restored)
        assertTrue("The original startup marker was already consumed", projections().isEmpty())
        restored.newIntent(HomeActivity.usbStartupIntent(app)); idle()
        assertEquals(1, projections().size)
        assertEquals(1, starts)
    }

    @Test fun theLauncherMarksOnlyUsbStartupHomesForAutomaticProjection() {
        connected()
        for (reason in listOf(CarLinkHost.OpenReason.USB_ATTACHED, CarLinkHost.OpenReason.UNPLUGGED)) {
            HomeLauncher.openHome(app, reason)
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(HomeActivity::class.java.name, intent.component!!.className)
            val controller = Robolectric.buildActivity(HomeActivity::class.java, intent)
                .also(activities::add).create().start().visible()
            readyHome(controller)
            assertEquals(if (reason == CarLinkHost.OpenReason.USB_ATTACHED) 1 else 0, projections().size)
        }
        assertEquals(1, starts)
    }

    @Test fun theActualApplicationDoesNotInitializeCredentialSettingsOrCoreWhileLocked() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val application = CarLinkApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, app))
        val profile = app.getSharedPreferences("f150_profile", Context.MODE_PRIVATE)
        assertTrue(profile.all.isEmpty())
        assertNull(ReflectionHelpers.getStaticField<Any?>(CarLinkCore::class.java, "installed"))
        try {
            application.onCreate(); idle()
            assertTrue("Application startup must defer credential preference writes", profile.all.isEmpty())
            assertNull("Core must not initialize before credential unlock",
                ReflectionHelpers.getStaticField<Any?>(CarLinkCore::class.java, "installed"))
        } finally {
            // Replace/cancel that exact queued initializer without executing production engine setup.
            AfterUserUnlock.run(app, application) {}.cancel()
        }
    }
}
