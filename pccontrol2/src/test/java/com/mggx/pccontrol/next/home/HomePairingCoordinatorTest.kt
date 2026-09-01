package com.mggx.pccontrol.next.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePairingCoordinatorTest {
    @After fun resetCoordinator() = HomePairingCoordinator.resetForTest()

    @Test fun tailnetRangeDetectionIsStrict() {
        assertTrue(TailscaleAddressProvider.isTailnetIpv4("100.64.0.1"))
        assertTrue(TailscaleAddressProvider.isTailnetIpv4("100.127.255.254"))
        assertFalse(TailscaleAddressProvider.isTailnetIpv4("100.128.0.1"))
        assertFalse(TailscaleAddressProvider.isTailnetIpv4("192.168.1.2"))
    }

    @Test fun activeOfferCanBecomeNullWhileCountdownUsesCapturedSnapshot() {
        val offer = HomePairingCoordinator.generate(8765, 1_000) { "100.64.1.2" }.getOrThrow()
        val capturedExpiry = offer.expiresAtEpochMs

        assertTrue(HomePairingCoordinator.sessions.consume(offer.secret, 1_001))
        assertEquals(HomeOfferPhase.CONSUMED, HomePairingCoordinator.state.value.phase)
        assertNull(HomePairingCoordinator.state.value.offer)
        assertFalse(offerCountdown(capturedExpiry, 1_002).expired)
    }

    @Test fun expirationDuringCountdownProducesStableExpiredState() {
        val offer = HomePairingCoordinator.generate(8765, 1_000) { "100.64.1.2" }.getOrThrow()
        HomePairingCoordinator.markExpired(offer.secret, offer.expiresAtEpochMs)
        assertEquals(HomeOfferPhase.EXPIRED, HomePairingCoordinator.state.value.phase)
        assertEquals(0L, offerCountdown(offer.expiresAtEpochMs, offer.expiresAtEpochMs).remainingSeconds)
    }

    @Test fun regenerationReplacesOfferAndEnsureReusesActiveOffer() {
        val first = HomePairingCoordinator.generate(8765, 1_000) { "100.64.1.2" }.getOrThrow()
        val reused = HomePairingCoordinator.ensure(8765, 1_001) { error("must not resolve another address") }.getOrThrow()
        assertSame(first, reused)
        val second = HomePairingCoordinator.generate(8765, 2_000) { "100.64.1.2" }.getOrThrow()
        assertNotEquals(first.secret, second.secret)
        assertEquals(second, HomePairingCoordinator.state.value.offer)
        assertEquals(HomeOfferPhase.ACTIVE, HomePairingCoordinator.state.value.phase)
    }

    @Test fun missingTailscaleAddressIsAnExplicitErrorState() {
        val result = HomePairingCoordinator.generate(8765, 1_000) { null }
        assertTrue(result.isFailure)
        assertEquals(HomeOfferPhase.ERROR, HomePairingCoordinator.state.value.phase)
        assertTrue(HomePairingCoordinator.state.value.message.orEmpty().contains("Tailscale"))
    }
}
