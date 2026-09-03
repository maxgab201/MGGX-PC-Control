package com.mggx.pccontrol.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import com.mggx.pccontrol.next.v2.OnboardingStep
import com.mggx.pccontrol.next.v2.WakeOnLanConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Alpha3HomeColdStartSeedTest {
    @Test
    fun seedPersistedHomePairControlOnly() = runBlocking {
        val store = NextSettingsStore(ApplicationProvider.getApplicationContext())
        store.selectRole(DeviceRole.HOME_PHONE)
        check(store.savePairedAgent(
            HomeDeviceConfig(
                enabled = true,
                port = 8765,
                pcId = "main",
                agentUrl = "http://192.0.2.10:8766",
                agentName = "MGGX PC",
                lanIp = "192.0.2.10",
                tailscaleIp = "100.64.0.10",
                agentVersion = "1.1.0",
                wakeOnLan = WakeOnLanConfig("02:00:00:00:00:01", "192.0.2.255", 9),
            ),
            "INSTRUMENTATION_ONLY_TOKEN",
        ))
        store.setStep(OnboardingStep.HOME_PAIR_CONTROL)
        Unit
    }
}
