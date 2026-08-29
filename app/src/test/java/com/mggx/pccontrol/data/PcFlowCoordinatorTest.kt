package com.mggx.pccontrol.data

import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcFlowCoordinatorTest {
    @Test fun offlineWakeThenOnlineSunshineOpensMoonlight() = runTest {
        val api = ScriptedRelay(listOf(pc(PcState.OFFLINE), pc(PcState.WAKING), pc(PcState.ONLINE, sunshine = true)))
        val outcome = PcFlowCoordinator(api, 0).openPc(100)
        assertEquals(OpenPcOutcome.MoonlightReady, outcome)
        assertEquals(1, api.wakeCalls)
    }

    @Test fun onlineWithStoppedSunshineRestartsOnce() = runTest {
        val api = ScriptedRelay(listOf(pc(PcState.ONLINE, sunshine = false, sunshineRestart = true), pc(PcState.ONLINE, sunshine = true)))
        assertEquals(OpenPcOutcome.MoonlightReady, PcFlowCoordinator(api, 0).openPc(100))
        assertEquals(1, api.sunshineRestartCalls)
    }

    @Test fun stoppedSunshineWithoutCapabilityExposesRecovery() = runTest {
        val api = ScriptedRelay(listOf(pc(PcState.ONLINE, sunshine = false, sunshineRestart = false)))
        val outcome = PcFlowCoordinator(api, 0).openPc(100)
        assertTrue(outcome is OpenPcOutcome.SunshineUnavailable)
        assertEquals(0, api.sunshineRestartCalls)
    }

    @Test fun wakeFailureIsNotReportedAsMoonlightReady() = runTest {
        val api = ScriptedRelay(listOf(pc(PcState.OFFLINE)), wakeResult = RelayResult.Failure(RelayError.Unauthorized))
        assertEquals(OpenPcOutcome.Failed(RelayError.Unauthorized), PcFlowCoordinator(api, 0).openPc(100))
    }

    @Test fun shutdownAndRestartWaitForConfirmedTransitions() = runTest {
        val shutdown = ScriptedRelay(listOf(pc(PcState.ONLINE), pc(PcState.OFFLINE)))
        assertTrue(PcFlowCoordinator(shutdown, 0).waitForOffline(PowerAction.SHUTDOWN, 100) is RelayResult.Success)
        val restart = ScriptedRelay(listOf(pc(PcState.ONLINE), pc(PcState.OFFLINE), pc(PcState.ONLINE)))
        assertTrue(PcFlowCoordinator(restart, 0).restartAndWait(100) is RelayResult.Success)
    }

    private fun pc(state: PcState, sunshine: Boolean = true, sunshineRestart: Boolean = true) = PcInfo(
        pcId = "main", name = "MGGX PC", state = state,
        agent = AgentStatus(state == PcState.ONLINE, "1.0.0", 1),
        sunshine = ServiceStatus(true, sunshine),
        capabilities = PcCapabilities(wake = true, shutdown = true, restart = true, sleep = true, hibernate = true, lock = true, sunshineRestart = sunshineRestart)
    )

    private class ScriptedRelay(private val script: List<PcInfo>, private val wakeResult: RelayResult<Unit> = RelayResult.Success(Unit, 202)) : PcRelayApi {
        private var index = 0; var wakeCalls = 0; var sunshineRestartCalls = 0
        override suspend fun getStatus(): RelayResult<PcInfo> = RelayResult.Success(script[(index++).coerceAtMost(script.lastIndex)])
        override suspend fun wake(): RelayResult<Unit> { wakeCalls++; return wakeResult }
        override suspend fun shutdown() = RelayResult.Success(Unit, 202)
        override suspend fun restart() = RelayResult.Success(Unit, 202)
        override suspend fun sleep() = RelayResult.Success(Unit, 202)
        override suspend fun hibernate() = RelayResult.Success(Unit, 202)
        override suspend fun lock() = RelayResult.Success(Unit, 202)
        override suspend fun restartSunshine(): RelayResult<Unit> { sunshineRestartCalls++; return RelayResult.Success(Unit, 202) }
    }
}
