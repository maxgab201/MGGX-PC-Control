package com.mggx.pccontrol.next

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.home.HomeDeviceRuntime
import com.mggx.pccontrol.next.home.HomeDeviceService
import com.mggx.pccontrol.next.home.HomePairingClient
import com.mggx.pccontrol.next.home.HomePairingCoordinator
import com.mggx.pccontrol.next.home.HomeClaimResult
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import com.mggx.pccontrol.next.v2.HomeRuntimeState
import com.mggx.pccontrol.next.v2.HomeServerState
import com.mggx.pccontrol.next.v2.OnboardingStep
import com.mggx.pccontrol.next.v2.WakeOnLanConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/** Hardware-crash reproducer: persisted HOME_PAIR_CONTROL starts the foreground home service. */
@RunWith(AndroidJUnit4::class)
class HomeServiceColdStartReproTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun stopService() {
        context.stopService(Intent(context, HomeDeviceService::class.java))
    }

    @Test
    fun seedPersistedHomePairControlOnly() {
        runBlocking {
            val store = NextSettingsStore(context)
            store.selectRole(DeviceRole.HOME_PHONE)
            seedPairedAgent(store, 18765)
            store.setStep(OnboardingStep.HOME_PAIR_CONTROL)
        }
    }

    @Test
    fun persistedHomePairControlStartsServiceAndActivitySurvives() {
        runBlocking {
            val store = NextSettingsStore(context)
            store.selectRole(DeviceRole.HOME_PHONE)
            seedPairedAgent(store, 18765)
            store.setStep(OnboardingStep.HOME_PAIR_CONTROL)

            ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java))
            delay(3_000)
            assertNotNull(HomeDeviceRuntime.state.value)

            ActivityScenario.launch(NextMainActivity::class.java).use { scenario ->
                scenario.onActivity { assertNotNull(it) }
            }
        }
    }

    @Test
    fun occupiedPortBecomesVisibleErrorWithoutKillingActivity() {
        runBlocking {
            context.stopService(Intent(context, HomeDeviceService::class.java))
            delay(500)
            ServerSocket(18766).use {
                val store = NextSettingsStore(context)
                store.saveHome(HomeDeviceConfig(enabled = true, port = 18766))
                ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java))
                delay(3_000)
                assertEquals(HomeRuntimeState.ERROR, HomeDeviceRuntime.state.value.state)
                ActivityScenario.launch(NextMainActivity::class.java).use { scenario ->
                    scenario.onActivity { assertNotNull(it) }
                }
            }
        }
    }

    @Test
    fun duplicateStartAndRestartDoNotCrashProcess() {
        runBlocking {
            context.stopService(Intent(context, HomeDeviceService::class.java))
            delay(500)
            NextSettingsStore(context).saveHome(HomeDeviceConfig(enabled = true, port = 18767))
            HomeDeviceService.start(context)
            HomeDeviceService.start(context)
            repeat(10) { HomeDeviceService.restart(context) }
            val runtime = withTimeoutOrNull(8_000) {
                HomeDeviceRuntime.state.first { it.serverState == HomeServerState.READY || it.serverState == HomeServerState.ERROR }
            }
            assertNotNull(runtime)
            assertTrue(runtime?.serverState != HomeServerState.ERROR)
        }
    }

    @Test
    fun completedHomeDashboardAutoStartsAndVerifiesLocalHealth() {
        runBlocking {
            context.stopService(Intent(context, HomeDeviceService::class.java))
            delay(500)
            val store = NextSettingsStore(context)
            store.selectRole(DeviceRole.HOME_PHONE)
            seedPairedAgent(store, 18768)
            store.complete()
            ActivityScenario.launch(NextMainActivity::class.java).use { scenario ->
                scenario.onActivity { assertNotNull(it) }
                val runtime = withTimeoutOrNull(8_000) {
                    HomeDeviceRuntime.state.first { it.serverState == HomeServerState.READY || it.serverState == HomeServerState.ERROR }
                }
                assertNotNull(runtime)
                assertEquals(HomeServerState.READY, runtime?.serverState)
                assertTrue(runtime?.localHealth == true)
                assertEquals(18768, runtime?.serverPort)
            }
        }
    }

    @Test
    fun readyHomeServerClaimsControllerOfferAndAnswersStatus() {
        runBlocking {
            context.stopService(Intent(context, HomeDeviceService::class.java))
            delay(500)
            val store = NextSettingsStore(context)
            store.saveHome(HomeDeviceConfig(enabled = true, port = 18769))
            HomeDeviceService.start(context)
            val runtime = withTimeoutOrNull(8_000) {
                HomeDeviceRuntime.state.first { it.serverState == HomeServerState.READY || it.serverState == HomeServerState.ERROR }
            }
            assertEquals(HomeServerState.READY, runtime?.serverState)
            val offer = HomePairingCoordinator.generate(18769) { "127.0.0.1" }.getOrThrow()
            val claimed = HomePairingClient(store).claim(offer)
            assertTrue(claimed is HomeClaimResult.Success)
        }
    }

    private suspend fun seedPairedAgent(store: NextSettingsStore, port: Int) {
        check(
            store.savePairedAgent(
                HomeDeviceConfig(
                    enabled = true,
                    port = port,
                    pcId = "main",
                    agentUrl = "http://192.0.2.10:8766",
                    agentName = "MGGX PC",
                    lanIp = "192.0.2.10",
                    tailscaleIp = "100.64.0.10",
                    agentVersion = "1.1.0",
                    wakeOnLan = WakeOnLanConfig(
                        "02:00:00:00:00:01",
                        "192.0.2.255",
                        9,
                    ),
                ),
                "INSTRUMENTATION_ONLY_TOKEN",
            ),
        )
    }
}
