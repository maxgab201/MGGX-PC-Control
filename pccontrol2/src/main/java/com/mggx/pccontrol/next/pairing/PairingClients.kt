package com.mggx.pccontrol.next.pairing

import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.home.AgentReply
import com.mggx.pccontrol.next.home.HttpAgentGateway
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import com.mggx.pccontrol.next.v2.PcPairingData
import com.mggx.pccontrol.next.v2.WakeOnLanConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface PcPairingResult {
    data class Success(val data: PcPairingData) : PcPairingResult
    data class AgentUpgradeRequired(val message: String) : PcPairingResult
    data class Failure(val message: String) : PcPairingResult
}

class PcAgentPairingClient(private val store: NextSettingsStore) {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun claim(offer: PcPairingOffer): PcPairingResult = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("http").host(offer.host).port(offer.port).addPathSegments("api/v1/pair/claim").build()
        val requestBody = JSONObject().put("protocolVersion", 1).put("secret", offer.secret)
            .put("client", "mggx-pc-control-home").toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 501) return@use PcPairingResult.AgentUpgradeRequired("Tu versión de MGGX PC Agent todavía no permite vinculación automática. Actualizá a 1.1 o usá la configuración temporal guiada.")
                if (response.code == 401) return@use PcPairingResult.Failure("El código venció o ya fue usado. Generá otro en la PC.")
                if (response.code !in 200..299) return@use PcPairingResult.Failure("La PC rechazó la vinculación (${response.code}).")
                val json = JSONObject(response.body?.string().orEmpty())
                val data = PcPairingData(
                    agentUrl = "http://${offer.host}:${json.optInt("agentPort", offer.port)}",
                    agentToken = json.optString("agentToken").trim(),
                    pcId = json.optString("pcId", "main").ifBlank { "main" },
                    name = json.optString("name", "MGGX PC").ifBlank { "MGGX PC" },
                    lanIp = json.optString("lanIp").trim(), tailscaleIp = json.optString("tailscaleIp").trim(),
                    agentVersion = json.optString("agentVersion").trim(), macAddress = json.optString("macAddress").trim(),
                    broadcastAddress = json.optString("broadcastAddress").trim(),
                )
                if (data.agentToken.isBlank() || data.lanIp.isBlank() || data.macAddress.isBlank() || data.broadcastAddress.isBlank()) return@use PcPairingResult.Failure("La PC respondió sin todos los datos necesarios para el encendido remoto.")
                val gateway = HttpAgentGateway(data.agentUrl, data.agentToken)
                val health = gateway.health()
                val status = gateway.status()
                if (health.code !in 200..299) return@use PcPairingResult.Failure("Encontramos la PC, pero su servicio no respondió correctamente.")
                if (status.code == 401 || status.code == 403) return@use PcPairingResult.Failure("La PC no aceptó la credencial recién creada.")
                if (status.code !in 200..299) return@use PcPairingResult.Failure("No pudimos comprobar el estado de la PC.")
                val config = HomeDeviceConfig(true, 8765, data.pcId, data.agentUrl, data.name, data.lanIp, data.tailscaleIp, data.agentVersion, WakeOnLanConfig(data.macAddress, data.broadcastAddress, 9))
                if (!store.savePairedAgent(config, data.agentToken)) PcPairingResult.Failure("No se pudo guardar la vinculación de forma segura.") else PcPairingResult.Success(data)
            }
        }.getOrElse { PcPairingResult.Failure("No encontramos MGGX PC Agent en la red de tu casa.") }
    }

    suspend fun configureLegacy(agentUrl: String, token: String, mac: String, broadcast: String): PcPairingResult {
        val cleanToken = token.trim()
        if (agentUrl.isBlank() || cleanToken.isBlank() || mac.isBlank() || broadcast.isBlank()) return PcPairingResult.Failure("Completá todos los datos temporales.")
        return runCatching {
            val gateway = HttpAgentGateway(agentUrl.trim(), cleanToken)
            val health: AgentReply = gateway.health(); val status = gateway.status()
            if (health.code !in 200..299) return@runCatching PcPairingResult.Failure("MGGX PC Agent no respondió en esa dirección.")
            if (status.code == 401 || status.code == 403) return@runCatching PcPairingResult.Failure("La credencial del Agent no es válida.")
            if (status.code !in 200..299) return@runCatching PcPairingResult.Failure("El Agent respondió, pero no pudimos leer su estado.")
            val json = JSONObject(status.body)
            val pc = json.optJSONObject("pc")
            val data = PcPairingData(agentUrl.trim().removeSuffix("/"), cleanToken, json.optString("pcId", "main"), pc?.optString("machineName")?.ifBlank { "MGGX PC" } ?: "MGGX PC", json.optString("lanIp"), json.optJSONObject("tailscale")?.optString("ip").orEmpty(), json.optString("agentVersion"), mac.trim(), broadcast.trim())
            val config = HomeDeviceConfig(true, 8765, data.pcId, data.agentUrl, data.name, data.lanIp, data.tailscaleIp, data.agentVersion, WakeOnLanConfig(data.macAddress, data.broadcastAddress, 9))
            if (!store.savePairedAgent(config, cleanToken)) PcPairingResult.Failure("No se pudo guardar la credencial.") else PcPairingResult.Success(data)
        }.getOrElse { PcPairingResult.Failure("No pudimos comprobar MGGX PC Agent. Revisá la dirección y la credencial.") }
    }
}

