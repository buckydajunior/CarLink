package com.carlink.core.connection

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.CarPlayHostActivity
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DiPlayEngineHooksForegroundTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val hooks by lazy { DiPlayEngineHooks(app) }
    private val activities = mutableListOf<ActivityController<out Activity>>()

    @Before fun installBeforeActivities() {
        CarPlayForegroundGate.install(app)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(emptyList())
    }

    @After fun clear() {
        hooks.stopSession {}
        shadowOf(Looper.getMainLooper()).idle()
        activities.forEach {
            runCatching { it.pause().stop().destroy() }
            CarPlayForegroundGate.onActivityDestroyed(it.get())
        }
    }

    private fun activity(resume: Boolean = true): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).create().start().also {
            activities += it
            if (resume) it.resume()
        }

    @Test fun alreadyResumedActivityLaunchesEvenWhenProcessImportanceHasNotCaughtUp() {
        val controller = activity()
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setProcesses(emptyList())

        assertNull(hooks.openHost(null))

        val opened = shadowOf(controller.get()).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, opened?.component?.className)
    }

    @Test fun foregroundProcessWithoutAResumedActivityStillWaitsForARealCaller() {
        val process = ActivityManager.RunningAppProcessInfo().apply {
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(process))
        assertNull(hooks.openHost(null))
        assertNull(shadowOf(app).nextStartedActivity)

        val controller = activity()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(controller.get()).nextStartedActivity?.component?.className)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun stoppingBeforeResumeCancelsEvenALateLifecycleCallback() {
        val controller = activity(resume = false)
        hooks.openHost(null)
        assertNull("The launch waits for resume", shadowOf(app).nextStartedActivity)
        // Android dispatches a snapshot: unregistering during an earlier callback cannot
        // retract a deferred launch callback already present in that snapshot.
        val callbacks = ReflectionHelpers.getField<ArrayList<Application.ActivityLifecycleCallbacks>>(
            app, "mActivityLifecycleCallbacks").toList()
        hooks.stopSession {}

        callbacks.forEach { it.onActivityResumed(controller.get()) }

        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun aNewRequestReplacesTheDeferredUsbIntent() {
        val controller = activity(resume = false)
        hooks.openHost(Intent("old-attach").putExtra("enumeration", "old"))
        hooks.openHost(Intent("new-attach").putExtra("enumeration", "new"))

        controller.resume()

        val opened = shadowOf(controller.get()).nextStartedActivity
        assertEquals("new-attach", opened?.action)
        assertEquals("new", opened?.getStringExtra("enumeration"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test @Config(sdk = [25])
    fun preAndroid10KeepsTheExistingBackgroundLaunchBehavior() {
        assertNull(hooks.openHost(null))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(app).nextStartedActivity?.component?.className)
    }

    @Test fun anImmediateActivityLaunchFailureIsReturnedToTheAdapter() {
        val controller = Robolectric.buildActivity(RefusingActivity::class.java).create().start().resume()
        activities += controller
        assertTrue(hooks.openHost(null) is ActivityNotFoundException)
    }

    @Test fun aDeferredActivityLaunchFailureIsCaughtAtResume() {
        val controller = Robolectric.buildActivity(RefusingActivity::class.java).create().start()
        activities += controller
        assertNull(hooks.openHost(null))
        controller.resume() // Failure is logged; it must not crash the resumed Activity.
        assertNull(shadowOf(app).nextStartedActivity)
    }

    class RefusingActivity : Activity() {
        override fun startActivity(intent: Intent) { throw ActivityNotFoundException("test refusal") }
    }
}
