package com.mggx.pccontrol.next.v2

/** The role is persisted before any pairing material is created. */
enum class DeviceRole { UNSELECTED, CONTROL_PHONE, HOME_PHONE }

enum class OnboardingStep {
    WELCOME, ROLE, CONTROL_PREPARE_PHONE, CONTROL_PREPARE_HOME_PHONE,
    HOME_PREPARE_TAILSCALE, HOME_ALWAYS_ON_VPN, HOME_BATTERY,
    HOME_PAIR_PC, HOME_PAIR_CONTROL, CONTROL_PREPARE_PC, CONTROL_PAIR_HOME, CONTROL_SUNSHINE, CONTROL_MOONLIGHT_LAN,
    CONTROL_MOONLIGHT_TAILSCALE, VERIFY, COMPLETE
}

data class OnboardingProgress(val current: Int, val total: Int)

object OnboardingFlow {
    val control = listOf(
        OnboardingStep.CONTROL_PREPARE_PHONE, OnboardingStep.CONTROL_PREPARE_HOME_PHONE,
        OnboardingStep.CONTROL_PREPARE_PC, OnboardingStep.CONTROL_PAIR_HOME,
        OnboardingStep.CONTROL_SUNSHINE, OnboardingStep.CONTROL_MOONLIGHT_LAN,
        OnboardingStep.CONTROL_MOONLIGHT_TAILSCALE, OnboardingStep.VERIFY,
    )
    val home = listOf(
        OnboardingStep.HOME_PREPARE_TAILSCALE, OnboardingStep.HOME_ALWAYS_ON_VPN,
        OnboardingStep.HOME_BATTERY, OnboardingStep.HOME_PAIR_PC,
        OnboardingStep.HOME_PAIR_CONTROL,
    )
    fun progress(role: DeviceRole, step: OnboardingStep): OnboardingProgress {
        val steps = if (role == DeviceRole.HOME_PHONE) home else control
        return OnboardingProgress((steps.indexOf(step).takeIf { it >= 0 } ?: 0) + 1, steps.size)
    }
}

enum class HomeRuntimeState {
    STOPPED, STARTING, READY, TAILSCALE_UNAVAILABLE, NETWORK_UNAVAILABLE,
    PC_OFFLINE, PC_ONLINE, AGENT_AUTH_ERROR, ERROR
}

/** State of the HTTP listener itself. A non-null Ktor engine is not enough evidence. */
enum class HomeServerState { STOPPED, STARTING, READY, ERROR }

data class WakeOnLanConfig(
    val macAddress: String = "",
    val broadcastAddress: String = "",
    val udpPort: Int = 9,
)

data class HomeDeviceConfig(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val pcId: String = "main",
    val agentUrl: String = "",
    val agentName: String = "MGGX PC",
    val lanIp: String = "",
    val tailscaleIp: String = "",
    val agentVersion: String = "",
    val wakeOnLan: WakeOnLanConfig = WakeOnLanConfig(),
)

data class PcPairingData(
    val agentUrl: String,
    val agentToken: String,
    val pcId: String,
    val name: String,
    val lanIp: String,
    val tailscaleIp: String,
    val agentVersion: String,
    val macAddress: String,
    val broadcastAddress: String,
)

enum class CheckState { PENDING, RUNNING, SUCCESS, FAILURE }
data class VerificationItem(val id: String, val label: String, val state: CheckState, val detail: String = "")

data class HomeRuntimeSnapshot(
    val state: HomeRuntimeState = HomeRuntimeState.STOPPED,
    val serverRunning: Boolean = false,
    val serverState: HomeServerState = HomeServerState.STOPPED,
    val localHealth: Boolean = false,
    val serverPort: Int? = null,
    val tailscaleIp: String? = null,
    val wifiAvailable: Boolean = false,
    val vpnActive: Boolean = false,
    val agentReachable: Boolean? = null,
    val lastError: String? = null,
)

sealed interface SetupResult {
    data object Success : SetupResult
    data class Failure(val message: String) : SetupResult
}
