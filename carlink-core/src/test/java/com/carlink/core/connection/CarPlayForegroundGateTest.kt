package com.carlink.core.connection

import android.app.Activity
import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlayForegroundGateTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val activities = mutableListOf<ActivityController<Activity>>()
    private val requests = mutableListOf<CarPlayForegroundGate.Request>()

    @Before fun installBeforeActivities() { CarPlayForegroundGate.install(app) }

    @After fun clear() {
        requests.forEach { it.cancel() }
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

    private fun request(action: (Activity) -> Unit) = CarPlayForegroundGate.request(app, action)
        .also { requests += it }

    @Test fun normalFutureResumeRunsThePendingRequestOnlyOnce() {
        val launches = mutableListOf<Activity>()
        val controller = activity(resume = false)
        request(launches::add)
        assertTrue(launches.isEmpty())
        controller.resume()
        assertEquals(listOf(controller.get()), launches)
        controller.pause().resume()
        assertEquals(1, launches.size)
    }

    @Test fun settingsFinishingAfterHomeAlreadyResumedUsesThatActivityImmediately() {
        val controller = activity()
        val launches = mutableListOf<Activity>()
        // Models the settings worker completing after Home's resume callback already happened.
        request(launches::add)
        assertEquals(listOf(controller.get()), launches)
    }

    @Test fun aLateLifecycleCallbackAfterCancellationDoesNotLaunch() {
        val controller = activity(resume = false)
        val launches = mutableListOf<Activity>()
        val pending = request(launches::add)
        pending.cancel()
        // The callback may already be present in Android's dispatch snapshot when canceled.
        CarPlayForegroundGate.onActivityResumed(controller.get())
        assertTrue(launches.isEmpty())
    }

    @Test fun cancellationFromAnotherThreadPreventsLaunchBeforeItsMainThreadCleanup() {
        val controller = activity(resume = false)
        val launches = mutableListOf<Activity>()
        val pending = request(launches::add)
        Thread { pending.cancel() }.apply { start(); join() }
        CarPlayForegroundGate.onActivityResumed(controller.get())
        assertTrue(launches.isEmpty())
    }

    @Test fun onlyTheNewestRequestRunsAndCancelingAnOlderTokenCannotCancelIt() {
        val controller = activity(resume = false)
        val launches = mutableListOf<String>()
        val older = request { launches += "old" }
        request { launches += "new" }
        older.cancel()
        controller.resume()
        assertEquals(listOf("new"), launches)
    }

    @Test fun pausingTheNewestActivityKeepsAnotherResumedActivityAvailable() {
        val first = activity()
        val second = activity()
        second.pause()
        val launches = mutableListOf<Activity>()
        request(launches::add)
        assertEquals(listOf(first.get()), launches)
    }

    @Test fun finishingActivitiesAreSkippedUntilAnotherActivityResumes() {
        activity().get().finish()
        val launches = mutableListOf<Activity>()
        request(launches::add)
        assertTrue(launches.isEmpty())
        val next = activity()
        assertEquals(listOf(next.get()), launches)
    }

    @Test fun pausingTheOnlyActivityDefersTheLaunchUntilItResumesAgain() {
        val controller = activity()
        controller.pause()
        val launches = mutableListOf<Activity>()
        request(launches::add)
        assertTrue(launches.isEmpty())
        controller.resume()
        assertEquals(listOf(controller.get()), launches)
    }

    @Test fun repeatingInstallForTheSameApplicationDoesNotLoseItsResumedActivity() {
        val controller = activity()
        CarPlayForegroundGate.install(app)
        val launches = mutableListOf<Activity>()
        request(launches::add)
        assertEquals(listOf(controller.get()), launches)
    }
}
