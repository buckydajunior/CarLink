package com.carlink.core.connection

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus
import java.util.concurrent.CopyOnWriteArrayList

/** The wired DiPlay host adapter, preserving stop completion and first-frame event semantics. */
class CarPlayAdapter internal constructor(private val hooks: CarPlayEngineHooks) : EngineAdapter {
    constructor(context: Context) : this(DiPlayEngineHooks(context.applicationContext))
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((AdapterEvent) -> Unit)? = null
    private var tracker: CarPlaySessionTracker? = null
    val lastFailure: CarPlayFailure? get() = tracker?.lastFailure
    private val statusListener: (CarPlayStatus?) -> Unit = { status ->
        val expected = tracker
        main.post { if (expected != null && tracker === expected && listener != null) deliver(expected.onStatus(status)) }
    }
    private val hostStateListener: (HostState) -> Unit = { state ->
        val expected = tracker
        main.post { if (expected != null && tracker === expected && listener != null) deliver(expected.onHostState(state)) }
    }
    override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
        if (hooks.hasSession()) { hooks.stopSession { start(plan, listener) }; return }
        detach()
        val next = CarPlaySessionTracker()
        tracker = next
        this.listener = listener
        hooks.addHostStateListener(hostStateListener)
        hooks.addStatusListener(statusListener)
        hooks.setWired()
        deliver(listOf(next.started()))
        hooks.openHost(plan.usbAttach)?.let { error ->
            deliver(listOf(AdapterEvent.Failed(FailureReason.ENGINE, error.message)))
            this.listener = null
            detach()
        }
    }
    override fun stop(onStopped: () -> Unit) {
        listener = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            if (listener == null) detach()
            onStopped()
        }
        val timeout = Runnable {
            com.carlink.core.diagnostics.EventLog.add("CarLink", "CarPlay stop did not finish in 20 s; going on")
            finish()
        }
        main.postDelayed(timeout, 20_000L)
        hooks.stopSession { main.post { main.removeCallbacks(timeout); finish() } }
    }
    override fun hasSession(): Boolean = hooks.hasSession()
    private fun deliver(events: List<AdapterEvent>) {
        events.forEach { event -> listener?.invoke(event) }
        if (tracker?.finished == true) { listener = null; detach() }
    }
    private fun detach() {
        hooks.removeStatusListener(statusListener)
        hooks.removeHostStateListener(hostStateListener)
    }
    companion object {
        fun install(context: Context) {
            val app = context.applicationContext
            CarPlayHostIntegration.accessoryName = CarLinkCore.config.accessoryName
            CarPlayHostIntegration.resetDebugLogsOnBootstrap = false
            CarPlayHostIntegration.vehicleIntegrations = false
            com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(app, false)
            CarPlayHostIntegration.onHostState = HostStates::dispatch
            com.shilapi.xcertplay.orchestration.CarPlayController.usbAttachAgeMillis =
                { com.carlink.core.detect.UsbAttachTimes.ageMillis(it.deviceName) }
            com.shilapi.xcertplay.orchestration.CarPlayController.resetWiredUsb = { UsbReset.reset(app, it) }
            CarPlayHostIntegration.liveSizeWidthsMm = emptyList()
            CarPlayWaitingView.install()
            com.shilapi.xcertplay.CarPlayRotation.setEnabled(app, com.carlink.core.detect.HeadUnitDetector.handheld(app))
        }
    }
}
interface CarPlayEngineHooks {
    fun hasSession(): Boolean
    fun stopSession(completion: () -> Unit)
    fun addStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun removeStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun addHostStateListener(listener: (HostState) -> Unit)
    fun removeHostStateListener(listener: (HostState) -> Unit)
    fun setWired()
    fun openHost(usbAttach: Intent?): Throwable?
}
object HostStates {
    private val listeners = CopyOnWriteArrayList<(HostState) -> Unit>()
    fun add(listener: (HostState) -> Unit) { listeners.addIfAbsent(listener) }
    fun remove(listener: (HostState) -> Unit) { listeners.remove(listener) }
    internal fun dispatch(state: HostState) = listeners.forEach { it(state) }
}
internal class DiPlayEngineHooks(private val app: Context) : CarPlayEngineHooks {
    override fun hasSession(): Boolean = CarPlayHostIntegration.hasSession()
    @Volatile private var pendingOpen: Application.ActivityLifecycleCallbacks? = null
    private fun cancelPendingOpen() {
        pendingOpen?.let { (app as Application).unregisterActivityLifecycleCallbacks(it) }
        pendingOpen = null
    }
    override fun stopSession(completion: () -> Unit) {
        cancelPendingOpen()
        if (Looper.myLooper() == Looper.getMainLooper()) CarPlayHostIntegration.stopSession(completion)
        else Handler(Looper.getMainLooper()).post { CarPlayHostIntegration.stopSession(completion) }
    }
    override fun addStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.addListener(listener)
    override fun removeStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.removeListener(listener)
    override fun addHostStateListener(listener: (HostState) -> Unit) = HostStates.add(listener)
    override fun removeHostStateListener(listener: (HostState) -> Unit) = HostStates.remove(listener)
    override fun setWired() = AirPlayPersistence.saveWirelessEnabled(app, false)
    override fun openHost(usbAttach: Intent?): Throwable? {
        // Auto-rotate may have changed since install: the turning canvas follows it at each start (#33).
        com.shilapi.xcertplay.CarPlayRotation.setEnabled(app, com.carlink.core.detect.HeadUnitDetector.handheld(app))
        val host = Intent(app, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // The host recognises a wired iPhone from the attach action and device extra and forces
        // wired (CarPlayHostActivity.isIphoneUsbAttachment; DIPLAY.md §2.4).
        usbAttach?.let { host.action = it.action; it.extras?.let(host::putExtras) }
        // Android silently drops an activity start from an app with no visible window (background
        // launch rules, Android 10+), e.g. an iPhone already plugged in while CarLink's process is
        // still starting. Open the host once one of CarLink's activities resumes.
        cancelPendingOpen() // only the newest start may open the screen
        if (!inFront()) {
            Log.i("CarLink-CarPlay", "CarLink not in front yet; opening CarPlay when it resumes")
            val application = app as Application
            pendingOpen = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    application.unregisterActivityLifecycleCallbacks(this)
                    if (pendingOpen === this) pendingOpen = null
                    runCatching { activity.startActivity(host) }
                        .onFailure { Log.w("CarLink-CarPlay", "deferred CarPlay open failed: ${it.javaClass.simpleName}") }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }.also { application.registerActivityLifecycleCallbacks(it) }
            return null
        }
        return runCatching { app.startActivity(host) }.exceptionOrNull()
    }

    // Only the caller's own processes are listed; IMPORTANCE_FOREGROUND is a resumed activity.
    private fun inFront(): Boolean = Build.VERSION.SDK_INT < 29 ||
        app.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
}
