package com.mggx.pccontrol.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Alpha3HomeColdStartSeedTest {
    @Test
    fun seedPersistedHomePairControlOnly() = runBlocking {
        val store = NextSettingsStore(ApplicationProvider.getApplicationContext())
        store.selectRole(DeviceRole.HOME_PHONE)
        store.saveHome(store.snapshot().home.copy(enabled = true))
        store.setStep(OnboardingStep.HOME_PAIR_CONTROL)
    }
}
