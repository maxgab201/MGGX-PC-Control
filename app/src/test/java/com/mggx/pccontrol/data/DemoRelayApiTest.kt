package com.mggx.pccontrol.data

import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRelayApiTest {
    @Test fun wakeTransitionsOfflineToOnlineAndDeclaresAgentServices() = runTest {
        var state = DemoStatus.OFFLINE
        val api = DemoRelayApi({ state }, { state = it }, 0)
        assertEquals(PcState.OFFLINE, (api.getStatus() as RelayResult.Success).value.state)
        assertEquals(202, (api.wake() as RelayResult.Success).httpCode)
        val online = (api.getStatus() as RelayResult.Success).value
        assertEquals(PcState.ONLINE, online.state)
        assertTrue(online.agent.reachable == true)
        assertTrue(online.sunshine.running == true)
    }

    @Test fun shutdownSleepAndHibernateFinishOffline() = runTest {
        listOf<suspend (DemoRelayApi) -> RelayResult<Unit>>({ it.shutdown() }, { it.sleep() }, { it.hibernate() }).forEach { call ->
            var state = DemoStatus.ONLINE
            val api = DemoRelayApi({ state }, { state = it }, 0)
            assertTrue(call(api) is RelayResult.Success)
            assertEquals(PcState.OFFLINE, (api.getStatus() as RelayResult.Success).value.state)
        }
    }

    @Test fun lockAndSunshineRestartDoNotPretendOffline() = runTest {
        val api = DemoRelayApi({ DemoStatus.ONLINE }, {}, 0)
        assertTrue(api.lock() is RelayResult.Success)
        assertTrue(api.restartSunshine() is RelayResult.Success)
    }
}
