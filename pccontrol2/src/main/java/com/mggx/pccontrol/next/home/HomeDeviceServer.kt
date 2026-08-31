package com.mggx.pccontrol.next.home

import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.pairing.PairingOffer
import com.mggx.pccontrol.next.pairing.PairingProtocol
import com.mggx.pccontrol.next.security.CredentialResult
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class AgentReply(val code: Int, val body: String)

sealed interface HomeClaimResult {
    data class Success(val pcId: String, val pcName: String) : HomeClaimResult
    data class Failure(val message: String) : HomeClaimResult
}

interface AgentGateway {
    suspend fun health(): AgentReply
    suspend fun status(): AgentReply
    suspend fun command(path: String): AgentReply
}

/** Home phone -> PC Agent. Authentication is kept in the home phone Keystore only. */
class HttpAgentGateway(private val baseUrl: String, private val token: String) : AgentGateway {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override suspend fun health() = request("health", false)
    override suspend fun status() = request("api/v1/status", true)
    override suspend fun command(path: String) = request(path, true, "POST")
    private suspend fun request(path: String, auth: Boolean, method: String = "GET"): AgentReply = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().removeSuffix("/")
        require(base.startsWith("http://") || base.startsWith("https://")) { "Agent URL invalid" }
        val request = Request.Builder().url("$base/$path")
            .method(method, if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType()))
            .apply { if (auth) header("Authorization", "Bearer $token") }.build()
        client.newCall(request).execute().use { AgentReply(it.code, it.body?.string().orEmpty()) }
    }
}

/**
 * Control phone -> home phone pairing exchange. The one-time QR secret is sent once over the
 * Tailnet/private network and is exchanged for a distinct controller credential. The credential
 * is written directly to the control-phone Keystore and never reaches DataStore or logs.
 */
class HomePairingClient(private val store: NextSettingsStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun claim(offer: PairingOffer): HomeClaimResult = withContext(Dispatchers.IO) {
        val url = runCatching {
            HttpUrl.Builder().scheme("http").host(offer.host).port(offer.port)
                .addPathSegments("api/v1/pair/claim").build()
        }.getOrElse { return@withContext HomeClaimResult.Failure("El código de vinculación tiene una dirección inválida.") }
        val body = JSONObject().put("secret", offer.secret).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code == 401) return@use HomeClaimResult.Failure("El código venció o ya fue usado. Generá uno nuevo en el celular de casa.")
                if (response.code !in 200..299) return@use HomeClaimResult.Failure("No se pudo vincular el celular de casa. Revisá que esté encendido y con la conexión segura activa.")
                val json = JSONObject(responseBody)
                val controllerToken = json.optString("controllerToken").trim()
                if (controllerToken.isBlank()) return@use HomeClaimResult.Failure("El celular de casa respondió sin una credencial válida.")
                val pcId = json.optString("pcId", "main").ifBlank { "main" }
                val pcName = json.optString("name", "MGGX PC").ifBlank { "MGGX PC" }
                if (!store.savePairedHome(offer.host, offer.port, pcName, controllerToken)) {
                    HomeClaimResult.Failure("No se pudo guardar la vinculación de forma segura.")
                } else HomeClaimResult.Success(pcId, pcName)
            }
        }.getOrElse { HomeClaimResult.Failure("No encontramos el celular que quedó en casa. Revisá Wi‑Fi y la conexión segura.") }
    }
}

object WakeOnLanSender {
    suspend fun send(mac: String, broadcast: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val bytes = mac.replace(":", "").replace("-", "").chunked(2).map { it.toInt(16).toByte() }
        require(bytes.size == 6 && port in 1..65_535 && broadcast.isNotBlank())
        val packet = ByteArray(102).also { output -> repeat(6) { output[it] = 0xff.toByte() }; repeat(16) { block -> bytes.forEachIndexed { index, b -> output[6 + block * 6 + index] = b } } }
        DatagramSocket().use { socket -> socket.broadcast = true; socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port)) }
        true
    }
}

class HomePairingSessions {
    private val lock = Any()
    private var active: PairingOffer? = null
    private val attempts = ArrayDeque<Long>()
    fun create(host: String, port: Int): PairingOffer = synchronized(lock) { PairingProtocol.createOffer(host, port, com.mggx.pccontrol.next.v2.DeviceRole.HOME_PHONE).also { active = it } }
    fun consume(secret: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        while (attempts.firstOrNull()?.let { now - it > 60_000L } == true) attempts.removeFirst()
        if (attempts.size >= 10) return false
        attempts.addLast(now)
        val offer = active ?: return false
        val valid = offer.expiresAtEpochMs > now && PairingProtocol.constantTimeEquals(offer.secret, secret)
        if (valid) active = null
        valid
    }
}

/**
 * Private HTTP server exposed through Tailscale. Health is unauthenticated; every control route
 * requires the controller credential and redirects are irrelevant because this is a server.
 */
class HomeDeviceServer(
    private val store: NextSettingsStore,
    private val sessions: HomePairingSessions = HomePairingSessions(),
) {
    private var engine: ApplicationEngine? = null
    val isRunning get() = engine != null

    suspend fun start(config: HomeDeviceConfig) {
        if (isRunning) return
        require(config.port in 1..65_535)
        engine = embeddedServer(CIO, host = "0.0.0.0", port = config.port) {
            routing {
                get("/health") { call.respondText("""{"ok":true,"service":"mggx-home-device","version":1}""", ContentType.Application.Json) }
                get("/api/v1/status") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardStatus(config) } }
                post("/api/v1/power/wake") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> wake(config) } }
                post("/api/v1/power/shutdown") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/power/shutdown") } }
                post("/api/v1/power/restart") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/power/restart") } }
                post("/api/v1/power/sleep") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/power/sleep") } }
                post("/api/v1/power/hibernate") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/power/hibernate") } }
                post("/api/v1/power/lock") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/power/lock") } }
                post("/api/v1/services/sunshine/restart") { call.authenticated({ token -> controllerAuthorized(token) }) { config -> forwardCommand(config, "api/v1/services/sunshine/restart") } }
                post("/api/v1/pair/claim") {
                    val secret = runCatching { JSONObject(call.receiveText()).optString("secret") }.getOrDefault("")
                    if (!sessions.consume(secret)) { call.respondText("""{"error":"pairing_invalid_or_expired"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
                    val token = randomToken()
                    if (!store.saveHomeControllerToken(token)) { call.respondText("""{"error":"credential_store_failed"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError); return@post }
                    val current = store.snapshot()
                    call.respondText(JSONObject().put("ok", true).put("pcId", current.home.pcId).put("name", current.home.agentName).put("controllerToken", token).toString(), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() { engine?.stop(500, 2_000); engine = null }
    fun createControllerOffer(host: String, port: Int): PairingOffer = sessions.create(host, port)

    private suspend fun controllerAuthorized(header: String?): Boolean = when (val stored = store.readHomeControllerToken()) {
        is CredentialResult.Value -> header?.removePrefix("Bearer ")?.let { PairingProtocol.constantTimeEquals(stored.value, it) } == true
        else -> false
    }

    private suspend fun ioGateway(config: HomeDeviceConfig): AgentGateway? = when (val token = store.readAgentToken()) {
        is CredentialResult.Value -> config.agentUrl.takeIf { it.isNotBlank() }?.let { HttpAgentGateway(it, token.value) }
        else -> null
    }
    private suspend fun forwardStatus(config: HomeDeviceConfig): AgentReply = ioGateway(config)?.status() ?: AgentReply(409, """{"error":"pc_agent_not_configured"}""")
    private suspend fun forwardCommand(config: HomeDeviceConfig, path: String): AgentReply = ioGateway(config)?.command(path) ?: AgentReply(409, """{"error":"pc_agent_not_configured"}""")
    private suspend fun wake(config: HomeDeviceConfig): AgentReply = runCatching {
        val wol = config.wakeOnLan
        if (wol.macAddress.isBlank() || wol.broadcastAddress.isBlank()) return AgentReply(409, """{"error":"wake_not_configured"}""")
        WakeOnLanSender.send(wol.macAddress, wol.broadcastAddress, wol.udpPort)
        AgentReply(202, """{"ok":true,"pcId":"${config.pcId}","state":"waking"}""")
    }.getOrElse { AgentReply(409, """{"error":"wake_not_configured"}""") }

    private suspend fun io.ktor.server.application.ApplicationCall.authenticated(call: suspend (String?) -> Boolean, handler: suspend (HomeDeviceConfig) -> AgentReply) {
        val header = request.headers[HttpHeaders.Authorization]
        if (!call(header)) { respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return }
        val reply = handler(store.snapshot().home)
        respondText(reply.body, ContentType.Application.Json, HttpStatusCode.fromValue(reply.code))
    }
}

private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
