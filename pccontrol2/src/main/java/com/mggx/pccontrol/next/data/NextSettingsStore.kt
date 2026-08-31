package com.mggx.pccontrol.next.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mggx.pccontrol.next.security.CredentialResult
import com.mggx.pccontrol.next.security.NextSecureCredentialStore
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import com.mggx.pccontrol.next.v2.OnboardingStep
import com.mggx.pccontrol.next.v2.WakeOnLanConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nextDataStore by preferencesDataStore("mggx_pc_control2_settings")

data class NextSettings(
    val role: DeviceRole = DeviceRole.UNSELECTED,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val setupComplete: Boolean = false,
    val home: HomeDeviceConfig = HomeDeviceConfig(),
    val pairedHomeHost: String = "",
    val pairedHomePort: Int = 8765,
    val pairedPcName: String = "MGGX PC",
    val legacyUrl: String = "",
    val legacyPcId: String = "main",
)

class NextSettingsStore(private val context: Context) {
    private val credentials = NextSecureCredentialStore(context)
    private object Key {
        val role = stringPreferencesKey("role"); val step = stringPreferencesKey("step"); val complete = booleanPreferencesKey("complete")
        val homeEnabled = booleanPreferencesKey("home_enabled"); val homePort = intPreferencesKey("home_port"); val agentUrl = stringPreferencesKey("agent_url")
        val pcId = stringPreferencesKey("pc_id"); val pcName = stringPreferencesKey("pc_name"); val wolMac = stringPreferencesKey("wol_mac"); val wolBroadcast = stringPreferencesKey("wol_broadcast"); val wolPort = intPreferencesKey("wol_port")
        val pairedHost = stringPreferencesKey("paired_home_host"); val pairedPort = intPreferencesKey("paired_home_port"); val pairedName = stringPreferencesKey("paired_pc_name")
        val legacyUrl = stringPreferencesKey("legacy_url"); val legacyPcId = stringPreferencesKey("legacy_pc_id")
    }

    val settings: Flow<NextSettings> = context.nextDataStore.data.map { p ->
        val role = enumOr(p[Key.role], DeviceRole.UNSELECTED)
        val step = enumOr(p[Key.step], if (role == DeviceRole.UNSELECTED) OnboardingStep.WELCOME else OnboardingStep.ROLE)
        NextSettings(
            role, step, p[Key.complete] ?: false,
            HomeDeviceConfig(p[Key.homeEnabled] ?: false, p[Key.homePort] ?: 8765, p[Key.pcId] ?: "main", p[Key.agentUrl] ?: "", p[Key.pcName] ?: "MGGX PC", WakeOnLanConfig(p[Key.wolMac] ?: "", p[Key.wolBroadcast] ?: "", p[Key.wolPort] ?: 9)),
            p[Key.pairedHost] ?: "", p[Key.pairedPort] ?: 8765, p[Key.pairedName] ?: "MGGX PC", p[Key.legacyUrl] ?: "", p[Key.legacyPcId] ?: "main"
        )
    }

    suspend fun selectRole(role: DeviceRole) = context.nextDataStore.edit { p ->
        p[Key.role] = role.name; p[Key.step] = if (role == DeviceRole.CONTROL_PHONE) OnboardingStep.CONTROL_PREPARE_PHONE.name else OnboardingStep.HOME_PREPARE_TAILSCALE.name; p[Key.complete] = false
    }
    suspend fun setStep(step: OnboardingStep) = context.nextDataStore.edit { it[Key.step] = step.name }
    suspend fun complete() = context.nextDataStore.edit { it[Key.complete] = true; it[Key.step] = OnboardingStep.COMPLETE.name }
    suspend fun resumeSetup(step: OnboardingStep) = context.nextDataStore.edit { it[Key.complete] = false; it[Key.step] = step.name }
    suspend fun saveHome(config: HomeDeviceConfig) = context.nextDataStore.edit { p ->
        p[Key.homeEnabled] = config.enabled; p[Key.homePort] = config.port; p[Key.pcId] = config.pcId; p[Key.agentUrl] = config.agentUrl; p[Key.pcName] = config.agentName
        p[Key.wolMac] = config.wakeOnLan.macAddress; p[Key.wolBroadcast] = config.wakeOnLan.broadcastAddress; p[Key.wolPort] = config.wakeOnLan.udpPort
    }
    suspend fun savePairedHome(host: String, port: Int, pcName: String, controllerToken: String): Boolean {
        if (!credentials.write("home_control", controllerToken)) return false
        context.nextDataStore.edit { p -> p[Key.pairedHost] = host; p[Key.pairedPort] = port; p[Key.pairedName] = pcName }; return true
    }
    suspend fun saveAgentToken(token: String) = credentials.write("agent", token)
    suspend fun saveHomeControllerToken(token: String) = credentials.write("home_controller", token)
    suspend fun readAgentToken() = credentials.read("agent")
    suspend fun readHomeControllerToken() = credentials.read("home_controller")
    suspend fun readPairedHomeToken() = credentials.read("home_control")
    suspend fun saveLegacy(url: String, pcId: String, token: String): Boolean {
        if (!credentials.write("legacy_relay", token.trim())) return false
        context.nextDataStore.edit { p -> p[Key.legacyUrl] = url.trim(); p[Key.legacyPcId] = pcId.trim().ifBlank { "main" } }; return true
    }
    suspend fun snapshot() = settings.first()
    suspend fun resetForRoleChange() = context.nextDataStore.edit { p -> p.clear() }
}

private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T = runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
