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
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class AgentReply(val code: Int, val body: String)

sealed interface HomeClaimResult {
    data class Success(val pcId: String, val pcName: String, val statusBody: String, val lanIp: String, val tailscaleIp: String) : HomeClaimResult
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
                if (response.code >= 500) return@use HomeClaimResult.Failure("El celular de casa respondió con un error interno (${response.code}).")
                if (response.code !in 200..299) return@use HomeClaimResult.Failure("El celular de casa rechazó la vinculación (${response.code}).")
                val json = JSONObject(responseBody)
                val controllerToken = json.optString("controllerToken").trim()
                if (controllerToken.isBlank()) return@use HomeClaimResult.Failure("El celular de casa respondió sin una credencial válida.")
                val pcId = json.optString("pcId", "main").ifBlank { "main" }
                val pcName = json.optString("name", "MGGX PC").ifBlank { "MGGX PC" }
                val lanIp = json.optString("lanIp").trim()
                val tailscaleIp = json.optString("tailscaleIp").trim()
                val healthRequest = Request.Builder().url(HttpUrl.Builder().scheme("http").host(offer.host).port(offer.port).addPathSegment("health").build()).build()
                val statusRequest = Request.Builder().url(HttpUrl.Builder().scheme("http").host(offer.host).port(offer.port).addPathSegments("api/v1/status").build()).header("Authorization", "Bearer $controllerToken").build()
                val healthOk = client.newCall(healthRequest).execute().use { it.code in 200..299 }
                val statusReply = client.newCall(statusRequest).execute().use { it.code to it.body?.string().orEmpty() }
                if (!healthOk) return@use HomeClaimResult.Failure("El celular de casa entregó la credencial, pero /health no respondió.")
                if (statusReply.first == 401 || statusReply.first == 403) return@use HomeClaimResult.Failure("La credencial recién creada no fue aceptada.")
                if (statusReply.first !in 200..299 && statusReply.first != 409) return@use HomeClaimResult.Failure("El celular de casa respondió, pero no pudimos comprobar el estado.")
                if (!store.savePairedHome(offer.host, offer.port, pcName, controllerToken, lanIp, tailscaleIp)) {
                    HomeClaimResult.Failure("No se pudo guardar la vinculación de forma segura.")
                } else HomeClaimResult.Success(pcId, pcName, statusReply.second, lanIp, tailscaleIp)
            }
        }.getOrElse { error -> HomeClaimResult.Failure(homeClaimNetworkMessage(error, offer.host, offer.port)) }
    }
}

internal fun homeClaimNetworkMessage(error: Throwable, host: String, port: Int): String = when (error) {
    is SocketTimeoutException -> "Se agotó el tiempo al contactar $host:$port. Revisá la conexión segura."
    is UnknownHostException -> "No pudimos resolver $host. Revisá la dirección del celular de casa."
    is NoRouteToHostException -> "No hay ruta hacia $host:$port. Revisá Tailscale en ambos celulares."
    is ConnectException -> if (error.message.orEmpty().contains("refused", ignoreCase = true)) "El celular de casa está disponible, pero su servicio no responde en $host:$port." else "No se pudo conectar a $host:$port. Revisá Wi‑Fi y Tailscale."
    else -> "No se pudo contactar el celular de casa en $host:$port (${error.javaClass.simpleName})."
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
    fun create(host: String, port: Int, nowMs: Long = System.currentTimeMillis()): PairingOffer = synchronized(lock) { PairingProtocol.createOffer(host, port, com.mggx.pccontrol.next.v2.DeviceRole.HOME_PHONE, nowMs).also { active = it } }
    fun consume(secret: String, now: Long = System.currentTimeMillis()): Boolean {
        val consumed = synchronized(lock) {
            while (attempts.firstOrNull()?.let { now - it > 60_000L } == true) attempts.removeFirst()
            if (attempts.size >= 10) return@synchronized false
            attempts.addLast(now)
            val offer = active ?: return@synchronized false
            val valid = offer.expiresAtEpochMs > now && PairingProtocol.constantTimeEquals(offer.secret, secret)
            if (valid) active = null
            valid
        }
        // Never call the coordinator while holding the session lock: generation takes the locks
        // in the opposite order and doing so could deadlock claim vs. regeneration.
        if (consumed) HomePairingCoordinator.clearIfConsumed(secret)
        return consumed
    }
    fun clear() = synchronized(lock) { active = null }
}

/**
 * Private HTTP server exposed through Tailscale. Health is unauthenticated; every control route
 * requires the controller credential and redirects are irrelevant because this is a server.
 */
class HomeDeviceServer(
    private val store: NextSettingsStore,
    private val sessions: HomePairingSessions = HomePairingCoordinator.sessions,
) {
    private val lifecycle = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var ready = false
    @Volatile private var lastStartError: String? = null
    val isRunning get() = ready
    val error get() = lastStartError

    suspend fun start(config: HomeDeviceConfig) {
        lifecycle.withLock {
            if (ready && localHealth(config.port)) return
            stopLocked()
            require(config.port in 1..65_535)
            ready = false
            lastStartError = null
            // CIO binds its socket asynchronously after start(wait = false) returns. Without this
            // synchronous probe, an occupied port escapes the caller's try/catch as an uncaught
            // DefaultDispatcher exception and kills the whole application process.
            ensurePortAvailable(config.port)
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
                    call.respondText(JSONObject().put("ok", true).put("pcId", current.home.pcId).put("name", current.home.agentName)
                        .put("lanIp", current.home.lanIp).put("tailscaleIp", current.home.tailscaleIp)
                        .put("controllerToken", token).toString(), ContentType.Application.Json)
                }
            }
            }.start(wait = false)
            if (!awaitLocalHealth(config.port)) {
                val message = "El servidor local no respondió en el puerto ${config.port}."
                stopLocked()
                lastStartError = message
                throw IllegalStateException(message)
            }
            ready = true
        }
    }

    private fun ensurePortAvailable(port: Int) {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("0.0.0.0", port))
        }
    }

    suspend fun stop() = lifecycle.withLock { stopLocked() }
    private fun stopLocked() {
        ready = false
        runCatching { engine?.stop(500, 2_000) }
        engine = null
    }
    suspend fun localHealth(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val health = Request.Builder()
                .url(HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).addPathSegment("health").build())
                .build()
            localClient.newCall(health).execute().use { response ->
                response.code in 200..299 && JSONObject(response.body?.string().orEmpty()).optBoolean("ok")
            }
        }.getOrDefault(false)
    }
    private suspend fun awaitLocalHealth(port: Int): Boolean {
        repeat(20) {
            if (localHealth(port)) return true
            delay(100)
        }
        return false
    }
    fun createControllerOffer(host: String, port: Int): PairingOffer = sessions.create(host, port)

    private suspend fun controllerAuthorized(header: String?): Boolean = when (val stored = store.readHomeControllerToken()) {
        is CredentialResult.Value -> header?.removePrefix("Bearer ")?.let { PairingProtocol.constantTimeEquals(stored.value, it) } == true
        else -> false
    }

    private suspend fun ioGateway(config: HomeDeviceConfig): AgentGateway? = when (val token = store.readAgentToken()) {
        is CredentialResult.Value -> config.agentUrl.takeIf { it.isNotBlank() }?.let { HttpAgentGateway(it, token.value) }
        else -> null
    }
    private suspend fun forwardStatus(config: HomeDeviceConfig): AgentReply {
        val reply = ioGateway(config)?.status() ?: return AgentReply(409, """{"error":"pc_agent_not_configured"}""")
        if (reply.code !in 200..299) return reply
        return runCatching {
            val source = JSONObject(reply.body)
            val pc = source.optJSONObject("pc")
            val power = source.optJSONObject("power")
            val normalized = JSONObject().put("ok", true).put("apiVersion", source.optInt("apiVersion", 1))
                .put("pcId", config.pcId).put("name", pc?.optString("machineName")?.ifBlank { config.agentName } ?: config.agentName)
                .put("state", pc?.optString("state")?.ifBlank { "online" } ?: source.optString("state", "online"))
                .put("agent", JSONObject().put("reachable", true).put("version", source.optString("agentVersion")).put("uptimeSeconds", pc?.optLong("uptimeSeconds")))
                .put("sunshine", source.optJSONObject("sunshine") ?: JSONObject.NULL)
                .put("tailscale", source.optJSONObject("tailscale") ?: JSONObject.NULL)
                .put("capabilities", JSONObject().put("wake", config.wakeOnLan.macAddress.isNotBlank()).put("shutdown", true).put("restart", true)
                    .put("sleep", power?.optBoolean("sleepSupported", false)).put("hibernate", power?.optBoolean("hibernateSupported", false)).put("lock", true).put("sunshineRestart", true))
            AgentReply(200, normalized.toString())
        }.getOrElse { AgentReply(502, """{"error":"invalid_agent_response"}""") }
    }
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

private val localClient = OkHttpClient.Builder()
    .connectTimeout(300, TimeUnit.MILLISECONDS)
    .readTimeout(500, TimeUnit.MILLISECONDS)
    .callTimeout(800, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
