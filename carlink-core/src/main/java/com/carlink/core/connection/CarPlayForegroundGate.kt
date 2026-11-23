package com.carlink.core.connection

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Main-thread lifecycle gate, installed before USB discovery. Process importance can lag an
 * Activity's resume, so it must not decide whether a wired host launch has a foreground caller.
 */
internal object CarPlayForegroundGate : Application.ActivityLifecycleCallbacks {
    private var application: Application? = null
    private val resumed = ArrayList<WeakReference<Activity>>()
    private var generation = 0L
    private var pending: Request? = null

    internal class Request internal constructor(
        internal val generation: Long,
        internal val launch: (Activity) -> Unit,
    ) {
        @Volatile internal var cancelled = false
        fun cancel() = CarPlayForegroundGate.cancel(this)
    }

    fun install(app: Application) {
        if (application === app) return
        application?.unregisterActivityLifecycleCallbacks(this)
        pending?.cancel()
        resumed.clear()
        application = app
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Replaces the previous request and also handles an Activity that already resumed. */
    fun request(app: Application, launch: (Activity) -> Unit): Request {
        install(app)
        pending?.cancel()
        val next = Request(++generation, launch)
        pending = next
        dispatch(next)
        return next
    }

    private fun cancel(request: Request) {
        request.cancelled = true
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { clearCancelled(request) }
        } else clearCancelled(request)
    }

    private fun clearCancelled(request: Request) {
        if (pending === request) {
            pending = null
            generation++
        }
    }

    private fun dispatch(request: Request) {
        val activity = currentActivity() ?: return
        // A queued lifecycle callback can arrive after stop or after a newer USB request.
        if (request.cancelled || pending !== request || request.generation != generation) return
        pending = null
        request.launch(activity)
    }

    private fun currentActivity(): Activity? {
        var current: Activity? = null
        val iterator = resumed.listIterator(resumed.size)
        while (iterator.hasPrevious()) {
            val activity = iterator.previous().get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) iterator.remove()
            else if (current == null) current = activity
        }
        return current
    }

    override fun onActivityResumed(activity: Activity) {
        forget(activity)
        resumed += WeakReference(activity)
        pending?.let(::dispatch)
    }

    private fun forget(activity: Activity) {
        resumed.removeAll { it.get() == null || it.get() === activity }
    }

    override fun onActivityPaused(activity: Activity) = forget(activity)
    override fun onActivityStopped(activity: Activity) = forget(activity)
    override fun onActivityDestroyed(activity: Activity) = forget(activity)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
