package com.mggx.pccontrol.next.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcPairingProtocolTest {
    private val secret = "a".repeat(43)

    @Test fun validPcAgentContractParsesAndPropagatesAddress() {
        val result = PcPairingProtocol.parse("mggx://pc-agent/v1?host=192.168.1.20&port=8766&secret=$secret&expires=5000", 1000) as PcPairingParseResult.Valid
        assertEquals("192.168.1.20", result.offer.host)
        assertEquals(8766, result.offer.port)
    }

    @Test fun expiredInvalidAndHomeCodesAreRejected() {
        assertTrue(PcPairingProtocol.parse("mggx://pc-agent/v1?host=192.168.1.20&port=8766&secret=$secret&expires=9", 10) is PcPairingParseResult.Invalid)
        assertTrue(PcPairingProtocol.parse("mggx://pc-agent/v1?host=192.168.1.20&port=8766&secret=bad&expires=99", 10) is PcPairingParseResult.Invalid)
        val home = PairingProtocol.createOffer("100.64.0.2", 8765, com.mggx.pccontrol.next.v2.DeviceRole.HOME_PHONE, 10)
        assertTrue(validatePairingQr(home.qrUri(), PairingQrKind.PC_AGENT).isFailure)
    }
}
