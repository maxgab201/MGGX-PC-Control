package com.mggx.pccontrol.data
import com.mggx.pccontrol.domain.*
import org.junit.Assert.*
import org.junit.Test

class RelayParsingTest{
    @Test fun parsesStatusAndMonitors(){val result=parsePcInfo("""{"pcId":"main","name":"MGGX PC","state":"online","lastSeen":"2026-08-27T12:00:00Z","monitors":[{"id":"1","name":"Principal","active":true}]}""");assertTrue(result is RelayResult.Success);val pc=(result as RelayResult.Success).value;assertEquals(PcState.ONLINE,pc.state);assertEquals("Principal",pc.monitors.single().name);assertTrue(pc.monitors.single().active)}
    @Test fun parsesOfflineStatusWithNullLastSeen(){val result=parsePcInfo("""{"ok":true,"pcId":"main","state":"offline","lastSeen":null,"monitors":[]}""");assertTrue(result is RelayResult.Success);assertEquals(PcState.OFFLINE,(result as RelayResult.Success).value.state);assertNull(result.value.lastSeen);assertTrue(result.value.monitors.isEmpty())}
    @Test fun malformedStatusIsFailure(){assertTrue(parsePcInfo("not-json") is RelayResult.Failure)}
}
