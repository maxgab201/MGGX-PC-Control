package com.mggx.pccontrol.next.pairing

import com.mggx.pccontrol.next.v2.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    @Test fun offerRoundTripsWithVersionedQrAndStrongSecret() {
        val offer = PairingProtocol.createOffer("100.64.10.4", 8765, DeviceRole.HOME_PHONE, nowMs = 1_000)
        assertEquals(43, offer.secret.length)
        val parsed = PairingProtocol.parse(offer.qrUri(), nowMs = 1_001) as PairingParseResult.Valid
        assertEquals("100.64.10.4", parsed.offer.host)
        assertEquals(8765, parsed.offer.port)
        assertEquals(DeviceRole.HOME_PHONE, parsed.offer.role)
    }

    @Test fun expiredWrongVersionAndMalformedSecretsAreRejected() {
        val expired = "mggx://pair/v1?host=100.64.1.2&port=8765&secret=${"a".repeat(43)}&expires=9&role=home_phone"
        assertTrue(PairingProtocol.parse(expired, nowMs = 10) is PairingParseResult.Invalid)
        val unsupported = "mggx://pair/v9?host=100.64.1.2&port=8765&secret=${"a".repeat(43)}&expires=99&role=home_phone"
        assertTrue(PairingProtocol.parse(unsupported, nowMs = 10) is PairingParseResult.Invalid)
        assertTrue(PairingProtocol.parse("https://example.invalid", nowMs = 10) is PairingParseResult.Invalid)
    }

    @Test fun pairingSessionIsSingleUseAndCodeIsNotCredential() {
        val sessions = com.mggx.pccontrol.next.home.HomePairingSessions()
        val offer = sessions.create("100.64.1.2", 8765)
        assertTrue(sessions.consume(offer.secret, offer.expiresAtEpochMs - 1))
        assertTrue(!sessions.consume(offer.secret, offer.expiresAtEpochMs - 1))
        assertTrue(!PairingProtocol.constantTimeEquals(offer.secret, offer.humanCode()))
    }
}
