package com.carlink.core.connection

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carlink.core.core.Engine
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

data class SessionRequest(val phoneName: String? = null, val usbAttach: Intent? = null) {
    val engine: Engine get() = Engine.CARPLAY
    val transport: Transport get() = Transport.USB
}
enum class Problem { PERMISSIONS, VPN_CONSENT, CARPLAY_AUTH_MISSING }
sealed interface SessionState {
    data object Idle : SessionState
    data class Blocked(val request: SessionRequest, val problems: List<Problem>) : SessionState
    data class Active(val request: SessionRequest, val attempt: Int, val phase: Phase, val notice: Notice? = null) : SessionState
    data class Failed(val request: SessionRequest, val reason: FailureReason, val detail: String?, val stage: ProgressStage? = null) : SessionState
    sealed interface Phase {
        data object Starting : Phase
        data class Progress(val step: Int, val stepCount: Int, val stage: ProgressStage) : Phase
        data class Retrying(val attempt: Int, val reason: FailureReason, val stage: ProgressStage = ProgressStage.WAITING_FOR_PHONE,
            val detail: String? = null) : Phase
        data object Connected : Phase
    }
    val engine: Engine? get() = if (this == Idle) null else Engine.CARPLAY
    val isActive: Boolean get() = this is Active
}
data class Notice(val kind: Kind, val stage: ProgressStage?, val message: String, val action: RecoveryAction? = null) {
    enum class Kind { PHONE_NOT_ANSWERING, SLOW, RECOVERING }
}

/** One wired session: ordered teardown, with internal USB configuration changes owned by the engine. */
class SessionManager(
    private val adapter: EngineAdapter,
    private val environment: Environment,
    private val applySettings: (SessionPlan) -> Unit,
    private val worker: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val profiles: ConnectionProfiles = ConnectionProfiles(),
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    interface Environment {
        fun preflight(request: SessionRequest): List<Problem>
        fun usbSerial(attach: Intent?): String? = null
        fun usbPhonePresent(): Boolean
        /** Fresh enumeration for a deferred replug; the original intent may name a retired node. */
        fun currentUsbAttach(previous: Intent?): Intent? = previous
        fun returnHome()
        fun log(message: String)
    }
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val health: StateFlow<List<PhoneHealth>> get() = profiles.health
    private var token = 0
    private var running = false
    private var stopping = 0
    private val afterStop = ArrayDeque<() -> Unit>()
    private var sessionId = 0
    private var attempt: Attempt? = null
    private val recoveries = mutableMapOf<ProgressStage?, Int>()
    private var everConnected = false
    private var quickRetries = 0
    private var driverStoppedAt = -REENUMERATION_WINDOW_MILLIS
    private var driverStoppedWithPhone = false
    private var automaticStartPaused = false
    private var replugRetryUsed = false
    private var connectedUsbNode: String? = null
    private data class UsbDeparture(val token: Int, val attempt: Attempt?, val node: String?,
        val wasConnected: Boolean, val graceAt: Long, val expiresAt: Long, var returned: SessionRequest? = null)
    private var usbDeparture: UsbDeparture? = null
    private data class PendingUsbAttach(val request: SessionRequest, val token: Int)
    private var pendingUsbAttach: PendingUsbAttach? = null
    private class Attempt(val request: SessionRequest, val plan: SessionPlan, now: Long) {
        var startedAt = now
        var stage = ProgressStage.WAITING_FOR_PHONE
        var stageSince = now
        var expiries = 0
        var hinted = false
        var totalFired = false
        var phoneSilent = false
        var connectedOnce = false
    }
    private val watchdog = Runnable { onWatchdog() }
    private val deferredUsbAttach = Runnable {
        val pending = pendingUsbAttach ?: return@Runnable
        pendingUsbAttach = null
        if (pending.token != token || automaticStartPaused || replugRetryUsed ||
            _state.value is SessionState.Active || !environment.usbPhonePresent()) return@Runnable
        val attach = environment.currentUsbAttach(pending.request.usbAttach) ?: return@Runnable
        replugRetryUsed = true
        note("USB replug guard expired; retrying the currently listed iPhone once")
        start(pending.request.copy(usbAttach = attach))
    }
    private val usbGoneCheck: Runnable = Runnable {
        val departure = usbDeparture ?: return@Runnable
        if (departure.token != token) return@Runnable
        if (!environment.usbPhonePresent()) {
            usbDeparture = null
            // A terminal transport callback can precede Android's detach broadcast. Its
            // anti-loop guard must not survive a subsequently confirmed cable departure.
            if (!automaticStartPaused) driverStoppedWithPhone = false
            replugRetryUsed = false
            clearPendingUsbAttach()
            if (_state.value is SessionState.Active || _state.value is SessionState.Blocked ||
                _state.value is SessionState.Failed) {
                note("USB iPhone departure confirmed; ending the session")
                stop()
                environment.returnHome()
            }
            return@Runnable
        }
        val current = _state.value as? SessionState.Active
        // An established CONTROL phase cannot consume a replacement USB node. Retain the
        // established-session grace, but let any engine-owned retry/progress win it.
        if (departure.wasConnected && departure.attempt === attempt &&
            current?.phase == SessionState.Phase.Connected && departure.node != null &&
            departure.node == connectedUsbNode) {
            val attach = environment.currentUsbAttach(departure.returned?.usbAttach ?: current.request.usbAttach)
            if (usbNode(attach)?.let { it != departure.node } == true) {
                usbDeparture = null
                note("USB iPhone returned on a replacement node; restarting after teardown")
                start((departure.returned ?: current.request).copy(usbAttach = attach))
                return@Runnable
            }
            // Hold only the recorded departure evidence while the bounded OEM enumeration
            // search runs. A later replacement attach can re-arm this already-mature check.
            if (clock() < departure.expiresAt) {
                main.postDelayed(usbGoneCheck, departure.expiresAt - clock())
                return@Runnable
            }
        }
        if (departure.wasConnected && departure.attempt === attempt && current != null &&
            clock() < departure.expiresAt) {
            // Engine-owned recovery wins the grace, but may still terminate later. Keep
            // its already-returned phone available for that terminal callback only.
            main.postDelayed(usbGoneCheck, departure.expiresAt - clock())
            return@Runnable
        }
        usbDeparture = null
    }
    init {
        if (adapter.hasSession()) {
            stopping++
            adapter.whenOrphanKnown { projecting -> main.post {
                if (projecting) { note("kept a running CarPlay session after process restart"); stopping--; drainAfterStop() }
                else adapter.stop { main.post { stopping--; drainAfterStop() } }
            } }
        }
    }
    /** False means the watcher/router must release its fresh handled claim. */
    fun onUsbPhone(kind: PhoneKind, phoneName: String?, attach: Intent?): Boolean {
        if (kind != PhoneKind.IPHONE) return false
        val request = SessionRequest(phoneName, attach)
        val departure = usbDeparture
        if (departure != null && departure.token == token && departure.node != null &&
            usbNode(attach)?.let { it != departure.node } == true) {
            departure.returned = request
            main.removeCallbacks(usbGoneCheck)
            main.postDelayed(usbGoneCheck, (departure.graceAt - clock()).coerceAtLeast(0))
        }
        val current = _state.value
        if (current is SessionState.Active) {
            val old = environment.usbSerial(current.request.usbAttach)
            val new = environment.usbSerial(attach)
            if (old != null && new != null && old != new) {
                note("another iPhone replaced the cable; switching after teardown")
                start(request)
            } else {
                // Keep a recorded detach/replug check alive. Canceling it here stranded a
                // Connected controller that cannot accept a new USB device itself.
                if (departure == null) main.removeCallbacks(usbGoneCheck)
                note("USB re-attach during CarPlay; left to the engine")
            }
            return true
        }
        if (driverStoppedWithPhone && clock() - driverStoppedAt < REENUMERATION_WINDOW_MILLIS) {
            deferUsbAttach(request)
            note("USB re-attach during stop guard; admission deferred or paused")
            return false
        }
        start(request)
        return true
    }
    fun connectUsb(phoneName: String? = null, attach: Intent? = null) {
        if (_state.value is SessionState.Active) return
        replugRetryUsed = false
        start(SessionRequest(phoneName, attach))
    }
    /** Wake/foreground scans can retry the same node without inventing a fresh USB attachment. */
    fun onUsbReady(kind: PhoneKind, phoneName: String?, attach: Intent?, retryFailed: Boolean = true): Boolean {
        if (kind != PhoneKind.IPHONE) return false
        if (_state.value is SessionState.Active) {
            return onUsbPhone(kind, phoneName, attach)
        }
        if (automaticStartPaused) {
            note("USB ready rescan; keeping the driver's disconnect")
            return false
        }
        // Detach settling is automatic presence recovery, not a foreground/wake retry.
        if (!retryFailed) return onUsbPhone(kind, phoneName, attach)
        note("USB ready rescan; retrying the listed iPhone")
        start(SessionRequest(phoneName, attach))
        return true
    }
    /** A cold USB launch needs a screen; internal re-enumeration must leave an existing host visible. */
    fun needsHostUi(): Boolean = _state.value is SessionState.Blocked || _state.value is SessionState.Failed || !adapter.hasSession()
    fun onUsbDetached(node: String? = null) {
        val shown = _state.value
        val active = shown as? SessionState.Active
        if (active?.phase == SessionState.Phase.Connected && node != null &&
            connectedUsbNode != null && node != connectedUsbNode) return
        val grace = if (active?.phase == SessionState.Phase.Connected || everConnected)
            USB_GONE_CONNECTED_GRACE_MILLIS else USB_GONE_GRACE_MILLIS
        usbDeparture = UsbDeparture(token, attempt, node,
            active?.phase == SessionState.Phase.Connected, clock() + grace, clock() + USB_REPLUG_SEARCH_MILLIS)
        main.removeCallbacks(usbGoneCheck)
        main.postDelayed(usbGoneCheck, grace)
    }
    fun retry() {
        val request = when (val shown = _state.value) {
            is SessionState.Blocked -> shown.request
            is SessionState.Failed -> shown.request
            is SessionState.Active -> shown.request
            SessionState.Idle -> return
        }
        replugRetryUsed = false
        start(request)
    }
    fun onSettingsChanged() { if (_state.value is SessionState.Blocked) retry() }
    fun disconnect() {
        automaticStartPaused = true
        val active = _state.value.isActive
        markDriverStop()
        stop()
        if (active) environment.returnHome()
    }
    fun stop() {
        token++
        endAttempt()
        main.removeCallbacks(usbGoneCheck)
        usbDeparture = null
        connectedUsbNode = null
        clearPendingUsbAttach()
        _state.value = SessionState.Idle
        if (running) { running = false; stopEngine() }
        drainAfterStop()
    }
    private fun markDriverStop() {
        driverStoppedWithPhone = _state.value is SessionState.Active && environment.usbPhonePresent()
        driverStoppedAt = clock()
    }
    private fun clearPendingUsbAttach() {
        pendingUsbAttach = null
        main.removeCallbacks(deferredUsbAttach)
    }
    private fun deferUsbAttach(request: SessionRequest) {
        if (automaticStartPaused) { note("USB replug kept paused by the driver's Disconnect"); return }
        if (replugRetryUsed) { note("USB replug recovery already used; waiting for a fresh departure or Connect"); return }
        pendingUsbAttach = PendingUsbAttach(request, token)
        main.removeCallbacks(deferredUsbAttach)
        note("USB replug retained until the automatic stop guard expires")
        main.postDelayed(deferredUsbAttach,
            (driverStoppedAt + REENUMERATION_WINDOW_MILLIS - clock()).coerceAtLeast(0) + 1)
    }
    private fun start(request: SessionRequest) {
        automaticStartPaused = false
        driverStoppedWithPhone = false
        sessionId++
        note("start CARPLAY USB")
        stop()
        recoveries.clear(); everConnected = false; quickRetries = 0
        begin(request, token)
    }
    private fun begin(request: SessionRequest, sessionToken: Int, phase: SessionState.Phase = SessionState.Phase.Starting,
        notice: Notice? = null, number: Int = 1) {
        val problems = environment.preflight(request)
        if (problems.isNotEmpty()) {
            _state.value = SessionState.Blocked(request, problems)
            note("CarPlay blocked: $problems")
            return
        }
        val plan = SessionPlan(request.phoneName, request.usbAttach, number)
        _state.value = SessionState.Active(request, number, phase, notice)
        running = true
        main.removeCallbacks(usbGoneCheck)
        usbDeparture = null
        whenStopped {
            if (sessionToken != token) return@whenStopped
            worker.execute {
                runCatching { applySettings(plan) }.onFailure { Log.w(TAG, "could not apply wired settings", it) }
                main.post {
                    if (sessionToken != token) return@post
                    val next = Attempt(request, plan, clock())
                    attempt = next
                    note("CARPLAY starting on USB (attempt $number)")
                    adapter.start(plan) { event -> main.post { onEvent(next, event) } }
                    armWatchdog()
                }
            }
        }
    }
    private fun onEvent(a: Attempt, event: AdapterEvent) {
        if (attempt !== a) return
        val current = _state.value as? SessionState.Active ?: return
        when (event) {
            is AdapterEvent.Progress -> {
                val now = clock(); val stage = ConnectionProfiles.normalize(event.stage)
                val forward = order(stage) > order(a.stage)
                if (stage != a.stage) {
                    if (order(stage) > order(a.stage) && !a.connectedOnce) profiles.recordStage(a.request, a.stage, now - a.stageSince)
                    a.stage = stage; a.stageSince = now; a.expiries = 0; a.hinted = false; a.phoneSilent = false
                }
                _state.value = current.copy(phase = SessionState.Phase.Progress(event.step, event.stepCount,
                    if (a.phoneSilent && stage == ProgressStage.WAITING_FOR_PHONE) ProgressStage.PHONE_NOT_ANSWERING else event.stage), notice = if (forward) null else current.notice)
                armWatchdog()
            }
            is AdapterEvent.Retrying -> {
                if (current.phase == SessionState.Phase.Connected) {
                    a.startedAt = clock(); a.stageSince = clock(); a.stage = ProgressStage.WAITING_FOR_PHONE
                    a.totalFired = false; a.expiries = 0; a.hinted = false
                }
                _state.value = current.copy(phase = SessionState.Phase.Retrying(event.attempt, event.reason, a.stage,
                    event.detail ?: event.reason.retryText()))
                armWatchdog()
            }
            AdapterEvent.Connected -> {
                main.removeCallbacks(watchdog)
                main.removeCallbacks(usbGoneCheck)
                usbDeparture = null
                val took = clock() - a.startedAt
                if (!a.connectedOnce) {
                    a.connectedOnce = true
                    profiles.recordStage(a.request, a.stage, clock() - a.stageSince)
                    profiles.recordSuccess(a.request, took)
                }
                everConnected = true
                replugRetryUsed = false
                connectedUsbNode = usbNode(environment.currentUsbAttach(a.request.usbAttach))
                note("CARPLAY connected on USB in $took ms")
                _state.value = current.copy(phase = SessionState.Phase.Connected, notice = null)
            }
            is AdapterEvent.Stalled -> {
                if (quickRetries < MAX_QUICK_RETRIES) {
                    quickRetries++
                    profiles.recordFailure(a.request, event.reason.name, a.stage, event.detail)
                    replaceAttempt(a, SessionState.Phase.Retrying(a.plan.attempt + 1, event.reason, a.stage, event.detail))
                } else { a.expiries++; recover(a, a.stage, clock() - a.stageSince) }
            }
            is AdapterEvent.Restart -> replaceAttempt(a, SessionState.Phase.Starting)
            is AdapterEvent.Ended -> {
                val returned = usbDeparture?.returned
                if (event.byUser) automaticStartPaused = true
                if (!event.byUser && !environment.usbPhonePresent()) environment.returnHome()
                markDriverStop(); stop()
                if (returned != null) deferUsbAttach(returned)
            }
            is AdapterEvent.Failed -> {
                profiles.recordFailure(a.request, event.reason.name, a.stage, event.detail)
                failSession(a, event.reason, event.detail)
            }
        }
    }
    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        val a = attempt ?: return
        if ((_state.value as? SessionState.Active)?.phase == SessionState.Phase.Connected) return
        var due = a.stageSince + profiles.deadline(a.request, a.stage) * (a.expiries + 1)
        if (!a.hinted) due = minOf(due, a.stageSince + minOf(AdaptiveDeadlines.HINT_MILLIS, profiles.deadline(a.request, a.stage)))
        if (!a.totalFired) due = minOf(due, a.startedAt + profiles.deadline(a.request, null))
        main.postDelayed(watchdog, (due - clock()).coerceAtLeast(0))
    }
    private fun onWatchdog() {
        val a = attempt ?: return
        val current = _state.value as? SessionState.Active ?: return
        if (current.phase == SessionState.Phase.Connected) return
        val elapsed = clock() - a.stageSince
        val deadline = profiles.deadline(a.request, a.stage)
        if (!a.hinted && elapsed >= minOf(AdaptiveDeadlines.HINT_MILLIS, deadline)) {
            a.hinted = true
            a.phoneSilent = a.stage == ProgressStage.WAITING_FOR_PHONE
            _state.value = current.copy(notice = Notice(if (a.phoneSilent) Notice.Kind.PHONE_NOT_ANSWERING else Notice.Kind.SLOW,
                a.stage, "${StallPlaybook.phaseName(a.stage)} has taken ${elapsed / 1000} s"))
        }
        if (elapsed >= deadline * (a.expiries + 1)) { a.expiries++; recover(a, a.stage, elapsed) }
        if (attempt === a && !a.totalFired && clock() - a.startedAt >= profiles.deadline(a.request, null)) {
            a.totalFired = true; recover(a, null, clock() - a.startedAt)
        }
        if (attempt === a) armWatchdog()
    }
    private fun recover(a: Attempt, stage: ProgressStage?, waited: Long) {
        val (action, used) = StallPlaybook.next(StallPlaybook.actions(stage), recoveries[stage] ?: 0)
        recoveries[stage] = used
        val message = "${StallPlaybook.phaseName(stage)} took ${waited / 1000} s: ${StallPlaybook.actionText(action)}"
        note("stall: CARPLAY USB $stage; $message")
        when (action) {
            RecoveryAction.NUDGE -> {
                adapter.recover(action)
                profiles.recordProblem(a.request, stage, AttemptOutcome.stallKind(stage), message)
            }
            RecoveryAction.RETRY_STEP -> {
                profiles.recordFailure(a.request, AttemptOutcome.stallKind(stage), stage, message)
                replaceAttempt(a, SessionState.Phase.Retrying(a.plan.attempt + 1, StallPlaybook.failure(stage, waited).first, a.stage, message),
                    Notice(Notice.Kind.RECOVERING, stage, message, action))
            }
            RecoveryAction.ASK_REPLUG, RecoveryAction.GIVE_UP -> {
                profiles.recordFailure(a.request, AttemptOutcome.stallKind(stage), stage, message)
                val reason = if (action == RecoveryAction.ASK_REPLUG) FailureReason.USB else StallPlaybook.failure(stage, waited).first
                failSession(a, reason, message)
            }
        }
    }
    private fun replaceAttempt(a: Attempt, phase: SessionState.Phase, notice: Notice? = null) {
        endAttempt()
        running = false
        stopEngine()
        begin(a.request, token, phase, notice, a.plan.attempt + 1)
    }
    private fun failSession(a: Attempt, reason: FailureReason, detail: String?) {
        val returned = usbDeparture?.returned
        markDriverStop(); token++; endAttempt(); running = false; stopEngine()
        main.removeCallbacks(usbGoneCheck); usbDeparture = null; connectedUsbNode = null
        clearPendingUsbAttach()
        _state.value = SessionState.Failed(a.request, reason, detail, a.stage)
        if (returned != null) deferUsbAttach(returned)
    }
    private fun endAttempt() { attempt = null; main.removeCallbacks(watchdog) }
    private fun stopEngine() {
        stopping++
        val started = clock()
        adapter.stop { main.post { stopping--; note("CARPLAY stopped in ${clock() - started} ms"); drainAfterStop() } }
    }
    private fun whenStopped(action: () -> Unit) { if (stopping == 0) action() else afterStop.addLast(action) }
    private fun drainAfterStop() { while (stopping == 0 && afterStop.isNotEmpty()) afterStop.removeFirst().invoke() }
    private fun note(message: String) { val line = "$message #s$sessionId"; Log.i(TAG, line); environment.log(line) }
    companion object {
        private const val TAG = "CarLink-Session"
        const val USB_GONE_GRACE_MILLIS = 3_500L
        const val USB_GONE_CONNECTED_GRACE_MILLIS = 1_200L
        const val REENUMERATION_WINDOW_MILLIS = 10_000L
        const val NOT_ANSWERING_MILLIS = AdaptiveDeadlines.HINT_MILLIS
        const val MAX_QUICK_RETRIES = 2
        private const val USB_REPLUG_SEARCH_MILLIS = 30_000L
        private fun usbNode(attach: Intent?): String? =
            attach?.let { com.carlink.core.entry.UsbRouterActivity.usbDevice(it) }?.deviceName
        private fun order(stage: ProgressStage): Int = when (stage) {
            ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> 0
            ProgressStage.HANDSHAKE -> 1
            ProgressStage.STARTING_PROJECTION -> 2
        }
    }
}
fun FailureReason.retryText(): String = when (this) {
    FailureReason.LINK_TOO_WEAK -> "the connection dropped"
    FailureReason.PHONE_DID_NOT_ANSWER -> "the iPhone did not answer"
    FailureReason.USB -> "the USB link restarted"
    FailureReason.BLOCKED -> "a permission is missing"
    FailureReason.AUTHENTICATION -> "authentication failed"
    FailureReason.ENGINE -> "the engine restarted"
}
