package com.carlink.core.connection

/** Existing wired deadlines: pruning features does not shorten the phone's consent/re-enumeration budget. */
object AdaptiveDeadlines {
    const val FACTOR = 2.5
    const val MIN_SAMPLES = 3
    const val HINT_MILLIS = 10_000L
    data class Bounds(val default: Long, val min: Long, val max: Long)
    fun bounds(stage: ProgressStage?): Bounds = when (stage) {
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> Bounds(15_000, 8_000, 25_000)
        ProgressStage.HANDSHAKE -> Bounds(12_000, 6_000, 25_000)
        ProgressStage.STARTING_PROJECTION -> Bounds(20_000, 15_000, 30_000)
        null -> Bounds(75_000, 60_000, 120_000)
    }
    fun deadline(samples: List<Long>, bounds: Bounds): Long {
        val median = median(samples) ?: return bounds.default
        return if (samples.size < MIN_SAMPLES) bounds.default else (median * FACTOR).toLong().coerceIn(bounds.min, bounds.max)
    }
    fun median(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted(); val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
object StallPlaybook {
    fun actions(stage: ProgressStage?): List<RecoveryAction> = when (stage) {
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING ->
            listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.ASK_REPLUG)
        ProgressStage.HANDSHAKE -> listOf(RecoveryAction.NUDGE, RecoveryAction.RETRY_STEP, RecoveryAction.ASK_REPLUG)
        ProgressStage.STARTING_PROJECTION -> listOf(RecoveryAction.RETRY_STEP, RecoveryAction.ASK_REPLUG)
        null -> listOf(RecoveryAction.ASK_REPLUG)
    }
    fun next(actions: List<RecoveryAction>, used: Int): Pair<RecoveryAction, Int> =
        (actions.getOrNull(used) ?: RecoveryAction.GIVE_UP) to (used + 1)
    fun failure(stage: ProgressStage?, waitedMillis: Long): Pair<FailureReason, String> = when (stage) {
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> FailureReason.USB to
            "the iPhone did not answer over USB in ${waitedMillis / 1000} s: unlock it and accept the prompt, or reconnect the cable"
        ProgressStage.HANDSHAKE -> FailureReason.USB to "the iPhone stopped answering during the handshake (${waitedMillis / 1000} s)"
        ProgressStage.STARTING_PROJECTION -> FailureReason.LINK_TOO_WEAK to "no picture ${waitedMillis / 1000} s after the iPhone connected"
        null -> FailureReason.USB to "no picture after ${waitedMillis / 1000} s"
    }
    fun phaseName(stage: ProgressStage?): String = when (stage) {
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> "Reaching the iPhone"
        ProgressStage.HANDSHAKE -> "The handshake"
        ProgressStage.STARTING_PROJECTION -> "Starting the picture"
        null -> "Connecting"
    }
    fun actionText(action: RecoveryAction): String = when (action) {
        RecoveryAction.NUDGE -> "waiting for the iPhone"
        RecoveryAction.RETRY_STEP -> "trying again"
        RecoveryAction.ASK_REPLUG -> "unplug the iPhone and plug it in again"
        RecoveryAction.GIVE_UP -> "stopping"
    }
}
