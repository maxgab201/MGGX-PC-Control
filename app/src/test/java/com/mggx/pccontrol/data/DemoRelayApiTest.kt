package com.mggx.pccontrol.data
import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DemoRelayApiTest{
    @Test fun wakeTransitionsOfflineToOnline()=runTest{var state=DemoStatus.OFFLINE;val api=DemoRelayApi({state},{state=it},0);assertEquals(PcState.OFFLINE,(api.getStatus() as RelayResult.Success).value.state);assertTrue(api.wake() is RelayResult.Success);assertEquals(PcState.ONLINE,(api.getStatus() as RelayResult.Success).value.state)}
    @Test fun unknownMonitorFails()=runTest{val api=DemoRelayApi({DemoStatus.ONLINE},{},0);assertTrue(api.activateMonitor("99") is RelayResult.Failure)}
    @Test fun actionRequiresOnline()=runTest{val api=DemoRelayApi({DemoStatus.OFFLINE},{},0);assertTrue(api.action(RemoteAction.CAMERA) is RelayResult.Failure)}
}
