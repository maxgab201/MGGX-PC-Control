package com.mggx.pccontrol.data

import com.mggx.pccontrol.domain.PcState
import com.mggx.pccontrol.domain.RelayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayParsingTest {
    @Test fun parsesLegacyOfflineWithoutMonitors() {
        val result = parsePcInfo("""{"ok":true,"apiVersion":1,"pcId":"main","state":"offline","lastSeen":null,"monitors":[]}""") as RelayResult.Success
        assertEquals(PcState.OFFLINE, result.value.state)
        assertNull(result.value.lastSeen)
        assertNull(result.value.agent.reachable)
        assertTrue(result.value.capabilities.wake == null)
    }

    @Test fun parsesAgentAwareStatusAndCapabilities() {
        val result = parsePcInfo("""{"pcId":"main","name":"MGGX PC","state":"online","lastSeen":"2026-08-29T04:00:00Z","agent":{"reachable":true,"version":"1.0.0","uptimeSeconds":12345},"sunshine":{"installed":true,"running":true},"tailscale":{"installed":true,"running":true,"ip":"100.64.1.2"},"capabilities":{"wake":true,"shutdown":true,"restart":true,"sleep":true,"hibernate":false,"lock":true,"sunshineRestart":true}}""") as RelayResult.Success
        val pc = result.value
        assertEquals(PcState.ONLINE, pc.state)
        assertTrue(pc.agent.reachable == true)
        assertEquals("1.0.0", pc.agent.version)
        assertEquals(12_345, pc.agent.uptimeSeconds)
        assertFalse(pc.capabilities.hibernate == true)
        assertEquals("100.64.1.2", pc.tailscale.ip)
    }

    @Test fun preservesUnknownStateWithoutCreatingConnectionError() {
        val result = parsePcInfo("""{"state":"future_state"}""") as RelayResult.Success
        assertEquals(PcState.UNKNOWN, result.value.state)
    }

    @Test fun malformedOrInvalidDateIsInvalidResponse() {
        assertTrue(parsePcInfo("not-json") is RelayResult.Failure)
        assertTrue(parsePcInfo("""{"state":"online","lastSeen":"bad-date"}""") is RelayResult.Failure)
    }
}
