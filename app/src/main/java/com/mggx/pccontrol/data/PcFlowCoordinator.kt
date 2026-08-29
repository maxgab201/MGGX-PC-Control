package com.mggx.pccontrol.data

import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.delay

sealed interface OpenPcOutcome {
    data object MoonlightReady : OpenPcOutcome
    data class SunshineUnavailable(val pc: PcInfo) : OpenPcOutcome
    data class Failed(val error: RelayError) : OpenPcOutcome
    data object TimedOut : OpenPcOutcome
}

/** Pure Relay orchestration. Android only supplies UI callbacks and launches Moonlight after success. */
class PcFlowCoordinator(private val api: PcRelayApi, private val delayMs: Long = 2_000L) {
    suspend fun openPc(timeoutMs: Long = 120_000L, onState: (PcInfo, Long) -> Unit = { _, _ -> }): OpenPcOutcome {
        var current = when (val status = api.getStatus()) { is RelayResult.Success -> status.value; is RelayResult.Failure -> return OpenPcOutcome.Failed(status.error) }
        if (current.state == PcState.ONLINE && current.sunshine.running == true) return OpenPcOutcome.MoonlightReady
        if (current.state == PcState.OFFLINE) {
            when (val wake = api.wake()) { is RelayResult.Failure -> return OpenPcOutcome.Failed(wake.error); is RelayResult.Success -> Unit }
            current = current.copy(state = PcState.WAKING)
        }
        val started = System.nanoTime(); var sunshineRestarted = false
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMs) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            when (val result = api.getStatus()) {
                is RelayResult.Failure -> { delay(delayMs); continue }
                is RelayResult.Success -> current = result.value
            }
            onState(current, elapsed)
            if (current.state == PcState.ONLINE) {
                if (current.sunshine.running == true) return OpenPcOutcome.MoonlightReady
                if (!sunshineRestarted && current.capabilities.sunshineRestart == true) {
                    sunshineRestarted = true
                    when (val restart = api.restartSunshine()) { is RelayResult.Failure -> return OpenPcOutcome.Failed(restart.error); is RelayResult.Success -> Unit }
                } else if (sunshineRestarted || current.capabilities.sunshineRestart == false) return OpenPcOutcome.SunshineUnavailable(current)
            }
            delay(delayMs)
        }
        return OpenPcOutcome.TimedOut
    }

    suspend fun waitForOffline(action: PowerAction, timeoutMs: Long, onState: (PcInfo) -> Unit = {}): RelayResult<Unit> {
        val command = when (action) { PowerAction.SHUTDOWN -> api.shutdown(); PowerAction.RESTART -> api.restart(); PowerAction.SLEEP -> api.sleep(); PowerAction.HIBERNATE -> api.hibernate(); PowerAction.LOCK -> api.lock(); PowerAction.WAKE -> api.wake() }
        if (command is RelayResult.Failure || action == PowerAction.LOCK) return command
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMs) {
            when (val status = api.getStatus()) { is RelayResult.Failure -> Unit; is RelayResult.Success -> { onState(status.value); if (status.value.state == PcState.OFFLINE) return RelayResult.Success(Unit) } }
            delay(delayMs)
        }
        return RelayResult.Failure(RelayError.Timeout)
    }

    /** Restart is the only power action whose confirmed final state is online again. */
    suspend fun restartAndWait(timeoutMs: Long = 180_000L, onState: (PcInfo) -> Unit = {}): RelayResult<Unit> {
        when (val command = api.restart()) { is RelayResult.Failure -> return command; is RelayResult.Success -> Unit }
        val started = System.nanoTime(); var observedOffline = false
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMs) {
            when (val status = api.getStatus()) {
                is RelayResult.Failure -> Unit
                is RelayResult.Success -> {
                    onState(status.value)
                    if (status.value.state == PcState.OFFLINE) observedOffline = true
                    if (observedOffline && status.value.state == PcState.ONLINE) return RelayResult.Success(Unit)
                }
            }
            delay(delayMs)
        }
        return RelayResult.Failure(RelayError.Timeout)
    }
}
