package com.mggx.pccontrol.next.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePairingCoordinatorTest {
    @Test fun tailnetRangeDetectionIsStrict() {
        assertTrue(TailscaleAddressProvider.isTailnetIpv4("100.64.0.1"))
        assertTrue(TailscaleAddressProvider.isTailnetIpv4("100.127.255.254"))
        assertFalse(TailscaleAddressProvider.isTailnetIpv4("100.128.0.1"))
        assertFalse(TailscaleAddressProvider.isTailnetIpv4("192.168.1.2"))
    }
}
