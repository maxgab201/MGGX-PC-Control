package com.mggx.pccontrol.next.v2

/** The role is persisted before any pairing material is created. */
enum class DeviceRole { UNSELECTED, CONTROL_PHONE, HOME_PHONE }

enum class OnboardingStep {
    WELCOME, ROLE, CONTROL_PREPARE_PHONE, CONTROL_PREPARE_HOME_PHONE,
    HOME_PREPARE_TAILSCALE, HOME_ALWAYS_ON_VPN, HOME_BATTERY,
    HOME_PAIR_PC, CONTROL_PAIR_HOME, CONTROL_SUNSHINE, CONTROL_MOONLIGHT_LAN,
    CONTROL_MOONLIGHT_TAILSCALE, VERIFY, COMPLETE
}

enum class HomeRuntimeState {
    STOPPED, STARTING, READY, TAILSCALE_UNAVAILABLE, NETWORK_UNAVAILABLE,
    PC_OFFLINE, PC_ONLINE, AGENT_AUTH_ERROR, ERROR
}

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
    val wakeOnLan: WakeOnLanConfig = WakeOnLanConfig(),
)

data class HomeRuntimeSnapshot(
    val state: HomeRuntimeState = HomeRuntimeState.STOPPED,
    val serverRunning: Boolean = false,
    val wifiAvailable: Boolean = false,
    val vpnActive: Boolean = false,
    val agentReachable: Boolean? = null,
    val lastError: String? = null,
)

sealed interface SetupResult {
    data object Success : SetupResult
    data class Failure(val message: String) : SetupResult
}
