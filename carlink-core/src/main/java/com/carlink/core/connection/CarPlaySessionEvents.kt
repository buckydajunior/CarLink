package com.carlink.core.connection

import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** Failure classification for the wired engine's statuses. */
data class CarPlayFailure(val reason: FailureReason, val hard: Boolean, val message: String?, val stage: String? = null)
object CarPlayEventMapping {
    val stages = listOf(ProgressStage.WAITING_FOR_PHONE, ProgressStage.HANDSHAKE, ProgressStage.STARTING_PROJECTION)
    fun stageOf(status: CarPlayStatus): ProgressStage? = when (status) {
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission,
        CarPlayStatus.MfiReady, CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
        is CarPlayStatus.IphoneNotListed, CarPlayStatus.RequestingIphonePermission -> ProgressStage.WAITING_FOR_PHONE
        CarPlayStatus.SelectingConfiguration, CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.OpeningDataPaths, CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl -> ProgressStage.HANDSHAKE
        CarPlayStatus.AttachingNetwork, CarPlayStatus.RunningControl -> ProgressStage.STARTING_PROJECTION
        else -> null
    }
    fun classify(failure: CarPlayStatus.Failed?, lastStage: CarPlayStatus?): CarPlayFailure {
        val message = failure?.message
        val stage = lastStage?.javaClass?.simpleName
        if (message == null) return CarPlayFailure(FailureReason.USB, false, null, stage)
        if (message.contains("MFi", true) || message.contains("certificate", true) || message.contains("authentication", true)) {
            return CarPlayFailure(FailureReason.AUTHENTICATION, message.contains("permission", true), message, stage)
        }
        if (message.contains("USB permission", true) || message.contains("USB service is unavailable", true)) {
            return CarPlayFailure(FailureReason.USB, true, message, stage)
        }
        val reason = when (lastStage) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission -> FailureReason.AUTHENTICATION
            null, is CarPlayStatus.Failed, CarPlayStatus.ControlEnded -> FailureReason.ENGINE
            else -> FailureReason.USB
        }
        return CarPlayFailure(reason, false, message, stage)
    }
}

/** Only a rendered frame counts as connected; null bus statuses during internal restarts are ignored. */
class CarPlaySessionTracker(private val maxUnboundedRetries: Int = 5) {
    private var lastStage: CarPlayStatus? = null
    private var lastProgress: ProgressStage? = null
    private var projecting = false
    private var everConnected = false
    private var unboundedRetries = 0
    var finished = false
        private set
    var lastFailure: CarPlayFailure? = null
        private set
    fun started(): AdapterEvent = progress(ProgressStage.WAITING_FOR_PHONE)!!
    fun onStatus(status: CarPlayStatus?): List<AdapterEvent> {
        if (finished) return emptyList()
        return when (status) {
            null, is CarPlayStatus.Failed -> emptyList() // the preceding host-state event decides a failure
            CarPlayStatus.ControlEnded -> finish(if (everConnected) AdapterEvent.Ended(false)
                else failed(CarPlayFailure(FailureReason.PHONE_DID_NOT_ANSWER, true, "CarPlay control ended before projection")))
            else -> {
                lastStage = status
                if (projecting) emptyList() else listOfNotNull(CarPlayEventMapping.stageOf(status)?.let(::progress))
            }
        }
    }
    fun onHostState(state: HostState): List<AdapterEvent> {
        if (finished) return emptyList()
        return when (state) {
            is HostState.Retrying -> {
                projecting = false
                lastProgress = null
                val failure = CarPlayEventMapping.classify(state.failure, lastStage)
                lastFailure = failure
                lastStage = null
                if (failure.hard) finish(failed(failure))
                else if (state.maxAttempts != null) listOf(AdapterEvent.Retrying(state.attempt, failure.reason))
                else {
                    unboundedRetries++
                    if (unboundedRetries <= maxUnboundedRetries) listOf(AdapterEvent.Retrying(unboundedRetries, failure.reason))
                    else finish(if (everConnected && failure.reason in setOf(FailureReason.USB, FailureReason.LINK_TOO_WEAK))
                        AdapterEvent.Ended(false) else failed(failure))
                }
            }
            is HostState.RetriesStopped -> finish(failed(CarPlayEventMapping.classify(state.failure, lastStage)))
            HostState.VpnConsentDenied -> finish(failed(CarPlayFailure(FailureReason.BLOCKED, true, "VPN consent denied")))
            is HostState.PermissionsDenied -> finish(failed(CarPlayFailure(FailureReason.BLOCKED, true, "Permissions denied: ${state.permissions.joinToString()}")))
            is HostState.ForegroundServiceFailed -> finish(failed(CarPlayFailure(FailureReason.ENGINE, true, state.message)))
            HostState.AuthenticationUnavailable -> finish(failed(CarPlayFailure(FailureReason.AUTHENTICATION, true, "CarPlay authentication unavailable")))
            HostState.FirstFrame -> {
                projecting = true; everConnected = true; unboundedRetries = 0; lastProgress = null
                listOf(AdapterEvent.Connected)
            }
            HostState.Closed -> finish(AdapterEvent.Ended(true))
        }
    }
    private fun progress(stage: ProgressStage): AdapterEvent? {
        if (lastProgress?.let { CarPlayEventMapping.stages.indexOf(stage) <= CarPlayEventMapping.stages.indexOf(it) } == true) return null
        lastProgress = stage
        return AdapterEvent.Progress(CarPlayEventMapping.stages.indexOf(stage) + 1, CarPlayEventMapping.stages.size, stage)
    }
    private fun failed(failure: CarPlayFailure): AdapterEvent { lastFailure = failure; return AdapterEvent.Failed(failure.reason, failure.message) }
    private fun finish(event: AdapterEvent): List<AdapterEvent> { finished = true; return listOf(event) }
}
