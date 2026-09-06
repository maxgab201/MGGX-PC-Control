package com.mggx.pccontrol.next.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePortStrategyTest {
    @Test fun legacyRelayPortMigratesToDedicatedDefault() {
        assertEquals(HomePortStrategy.DEFAULT_PORT, HomePortStrategy.candidates(HomePortStrategy.LEGACY_RELAY_PORT).first())
    }

    @Test fun occupiedPreferredPortFallsThroughDedicatedRange() {
        val ports = HomePortStrategy.candidates(18765)
        assertEquals(18765, ports.first())
        assertEquals(18766, ports[1])
        assertTrue(ports.all { it in 18765..18785 })
        assertFalse(ports.contains(HomePortStrategy.LEGACY_RELAY_PORT))
    }
}
