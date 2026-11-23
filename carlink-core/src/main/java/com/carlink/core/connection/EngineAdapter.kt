package com.carlink.core.connection

import android.content.Intent
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport

/** One wired engine, with explicit asynchronous teardown completion. */
interface EngineAdapter {
    val engine: Engine get() = Engine.CARPLAY
    fun whenOrphanKnown(decide: (projecting: Boolean) -> Unit) = decide(false)
    fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit)
    fun stop(onStopped: () -> Unit)
    fun hasSession(): Boolean
    fun recover(action: RecoveryAction): Boolean = false
}
enum class RecoveryAction { NUDGE, RETRY_STEP, ASK_REPLUG, GIVE_UP }
data class SessionPlan(val phoneName: String? = null, val usbAttach: Intent? = null, val attempt: Int = 1) {
    val engine: Engine get() = Engine.CARPLAY
    val transport: Transport get() = Transport.USB
}
sealed interface AdapterEvent {
    data class Progress(val step: Int, val stepCount: Int, val stage: ProgressStage) : AdapterEvent
    data class Retrying(val attempt: Int, val reason: FailureReason, val detail: String? = null) : AdapterEvent
    data object Connected : AdapterEvent
    data class Ended(val byUser: Boolean) : AdapterEvent
    data class Stalled(val reason: FailureReason, val detail: String) : AdapterEvent
    data class Restart(val detail: String) : AdapterEvent
    data class Failed(val reason: FailureReason, val detail: String? = null) : AdapterEvent
}
enum class ProgressStage { WAITING_FOR_PHONE, HANDSHAKE, STARTING_PROJECTION, PHONE_NOT_ANSWERING }
enum class FailureReason { LINK_TOO_WEAK, PHONE_DID_NOT_ANSWER, USB, BLOCKED, AUTHENTICATION, ENGINE }
