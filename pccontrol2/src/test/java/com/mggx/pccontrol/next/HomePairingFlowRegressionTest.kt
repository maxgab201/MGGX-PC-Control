package com.mggx.pccontrol.next

import com.mggx.pccontrol.next.pairing.PcPairingResult
import com.mggx.pccontrol.next.v2.OnboardingStep
import com.mggx.pccontrol.next.v2.PcPairingData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePairingFlowRegressionTest {
    @Test fun successfulPcPairingAdvancesToHomeControllerPairing() {
        val data = PcPairingData("http://192.168.1.2:8766", "test-token", "main", "MGGX PC", "192.168.1.2", "100.64.1.3", "1.1", "AA:BB:CC:DD:EE:FF", "192.168.1.255")
        assertEquals(OnboardingStep.HOME_PAIR_CONTROL, NextViewModel.stepAfterPcPairing(PcPairingResult.Success(data)))
        assertEquals(OnboardingStep.HOME_PAIR_PC, NextViewModel.stepAfterPcPairing(PcPairingResult.Failure("failed")))
    }

    @Test fun homeControllerOfferStepNeverForceUnwrapsOfferState() {
        val candidates = listOf(
            File("src/main/java/com/mggx/pccontrol/next/NextMainActivity.kt"),
            File("pccontrol2/src/main/java/com/mggx/pccontrol/next/NextMainActivity.kt"),
        )
        val source = candidates.firstOrNull(File::isFile)?.readText()
        assertTrue("NextMainActivity.kt not found", source != null)
        assertFalse(source!!.contains("offer!!"))
    }
}
