package com.mggx.pccontrol.next.data

import android.util.Log
import com.mggx.pccontrol.next.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val TAG = "MggxRelay"

/** One normalizer for UI, widgets, tiles and all network requests. */
object RelayUrlNormalizer {
    fun normalize(input: String): RelayResult<RelayEndpoint> {
        val text = input.trim()
        if (text.isBlank() || text.any { it == '\u0000' || Character.getType(it) == Character.FORMAT.toInt() || it.isWhitespace() }) return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_VALIDATE"))
        val candidate = if ("://" in text) text else "${if (isPrivateOrTailnet(extractHost(text))) "http" else "https"}://$text"
        val parsed = candidate.toHttpUrlOrNull() ?: return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_PARSE"))
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_COMPONENTS", parsed.host, parsed.port))
        if (parsed.scheme == "http" && !isPrivateOrTailnet(parsed.host)) return RelayResult.Failure(RelayError.InsecurePublicHttp, RelayTechnical("URL_POLICY", parsed.host, parsed.port))
        val path = parsed.pathSegments.filter(String::isNotBlank)
        if (path !in listOf(emptyList(), listOf("health"), listOf("api", "v1"))) return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_BASE_PATH", parsed.host, parsed.port))
        return RelayResult.Success(RelayEndpoint(parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()))
    }
    private fun extractHost(value: String) = if (value.startsWith("[")) value.substringAfter('[').substringBefore(']') else value.substringBefore('/').substringBefore(':')
    private fun isPrivateOrTailnet(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".ts.net") || (!h.contains('.') && !h.contains(':'))) return true
        if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
        val p = h.split('.').mapNotNull(String::toIntOrNull)
        return p.size == 4 && p.all { it in 0..255 } && (p[0] == 10 || p[0] == 127 || p[0] == 100 && p[1] in 64..127 || p[0] == 172 && p[1] in 16..31 || p[0] == 192 && p[1] == 168)
    }
}

class DemoRelayApi(private val status: () -> DemoStatus, private val setStatus: (DemoStatus) -> Unit, private val delayMs: Long = 700) : PcRelayApi {
    private var current = status()
    private fun state() = when (current) { DemoStatus.OFFLINE -> PcState.OFFLINE; DemoStatus.WAKING -> PcState.WAKING; DemoStatus.ONLINE -> PcState.ONLINE; DemoStatus.SHUTTING_DOWN -> PcState.SHUTTING_DOWN; DemoStatus.RESTARTING -> PcState.RESTARTING; DemoStatus.ERROR -> PcState.ERROR }
    private fun set(next: DemoStatus) { current = next; setStatus(next) }
    override suspend fun getStatus(): RelayResult<PcInfo> = if (current == DemoStatus.ERROR) RelayResult.Failure(RelayError.Unknown("DEMO_ERROR")) else RelayResult.Success(PcInfo("main", "MGGX PC", state(), System.currentTimeMillis(), AgentStatus(current == DemoStatus.ONLINE, "1.0.0", 12_345), ServiceStatus(true, current == DemoStatus.ONLINE), PcTailscaleStatus(true, current == DemoStatus.ONLINE, "100.64.0.10"), PcCapabilities(true, true, true, true, true, true, true)))
    override suspend fun wake(): RelayResult<Unit> { set(DemoStatus.WAKING); delay(delayMs); set(DemoStatus.ONLINE); return RelayResult.Success(Unit, 202) }
    override suspend fun shutdown(): RelayResult<Unit> { set(DemoStatus.SHUTTING_DOWN); delay(delayMs); set(DemoStatus.OFFLINE); return RelayResult.Success(Unit, 202) }
    override suspend fun restart(): RelayResult<Unit> { set(DemoStatus.RESTARTING); delay(delayMs); set(DemoStatus.ONLINE); return RelayResult.Success(Unit, 202) }
    override suspend fun sleep() = shutdown(); override suspend fun hibernate() = shutdown(); override suspend fun lock() = RelayResult.Success(Unit, 202); override suspend fun restartSunshine() = if (current == DemoStatus.ONLINE) RelayResult.Success(Unit, 202) else RelayResult.Failure(RelayError.Functional(409, "pc_offline"))
}

class HttpRelayApi(private val config: RelayConfig, private val clientOverride: OkHttpClient? = null) : PcRelayApi {
    private val client by lazy { clientOverride ?: OkHttpClient.Builder().connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS).readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS).callTimeout(config.timeoutSeconds.toLong() + 2, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    fun endpoint() = RelayUrlNormalizer.normalize(config.url)
    suspend fun getHealth(): RelayResult<RelayHealth> = request("health", authenticated = false).flatMap { body, code, time -> runCatching { val json = JSONObject(body); val version = json.optInt("version", json.optInt("apiVersion", -1)); val service = json.optString("service"); require(json.optBoolean("ok", false) && service in setOf("mggx-relay", "mggx-home-device") && version >= 1); RelayResult.Success(RelayHealth(version), code, time) }.getOrElse { RelayResult.Failure(RelayError.InvalidResponse, RelayTechnical("HEALTH_PARSE", elapsedMs = time)) } }
    override suspend fun getStatus() = request("api/v1/status").flatMap { body, code, time -> parsePcInfo(body, config.pcId, code, time) }
    override suspend fun wake() = command("api/v1/power/wake"); override suspend fun shutdown() = command("api/v1/power/shutdown"); override suspend fun restart() = command("api/v1/power/restart")
    override suspend fun sleep() = command("api/v1/power/sleep"); override suspend fun hibernate() = command("api/v1/power/hibernate"); override suspend fun lock() = command("api/v1/power/lock"); override suspend fun restartSunshine() = command("api/v1/services/sunshine/restart")
    private suspend fun command(path: String) = request(path, "POST").flatMap { _, code, time -> RelayResult.Success(Unit, code, time) }
    private suspend fun request(path: String, method: String = "GET", authenticated: Boolean = true): RelayResult<String> = withContext(Dispatchers.IO) {
        val started = System.nanoTime(); val endpoint = when (val e = endpoint()) { is RelayResult.Success -> e.value; is RelayResult.Failure -> return@withContext e }; val elapsed = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) }
        try {
            val target = endpoint.url.newBuilder().addPathSegments(path).build(); val builder = Request.Builder().url(target).method(method, if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType()))
            if (authenticated && config.token.isNotBlank()) builder.header("Authorization", "Bearer ${config.token.trim()}")
            runCatching { Log.d(TAG, "Relay request: stage=HTTP host=${endpoint.host} port=${endpoint.port} scheme=${endpoint.scheme} path=/$path") }
            client.newCall(builder.build()).execute().use { response -> val body = response.body?.string().orEmpty(); if (response.isSuccessful) RelayResult.Success(body, response.code, elapsed()) else RelayResult.Failure(httpError(response.code, body), RelayTechnical("HTTP", endpoint.host, endpoint.port, response.code, elapsedMs = elapsed())) }
        } catch (e: IllegalArgumentException) { RelayResult.Failure(RelayError.InvalidToken, technical("HTTP_HEADER", endpoint, e, elapsed())) } catch (e: Throwable) { RelayResult.Failure(classifyThrowable(e), technical("HTTP", endpoint, e, elapsed())) }
    }
    private fun technical(stage: String, endpoint: RelayEndpoint, e: Throwable, elapsed: Long) = RelayTechnical(stage, endpoint.host, endpoint.port, exceptionType = e.javaClass.simpleName, message = sanitize(e.message), elapsedMs = elapsed)
}
data class RelayHealth(val version: Int)
private fun httpError(code: Int, body: String): RelayError = when (code) { 401 -> RelayError.Unauthorized; 403 -> RelayError.Forbidden; 404 -> RelayError.ApiVersionMismatch; 429 -> RelayError.RateLimited; 409 -> RelayError.Functional(code, jsonError(body)); in 500..599 -> RelayError.Server(code); else -> RelayError.Functional(code, jsonError(body)) }
private fun jsonError(body: String) = runCatching { JSONObject(body).optString("error").takeIf(String::isNotBlank) }.getOrNull()
internal fun classifyThrowable(e: Throwable): RelayError = when (e) { is SocketTimeoutException -> RelayError.Timeout; is UnknownHostException -> RelayError.DnsFailure; is NoRouteToHostException -> RelayError.NoRoute; is ConnectException -> if (e.message?.contains("refused", true) == true) RelayError.ConnectionRefused else RelayError.NoRoute; is SecurityException -> RelayError.NetworkPermissionDenied; else -> if (e.message?.contains("EPERM", true) == true || e.message?.contains("permission denied", true) == true) RelayError.NetworkPermissionDenied else RelayError.Unknown(e.javaClass.simpleName) }
internal fun sanitize(message: String?) = message?.replace(Regex("[\\r\\n]+"), " ")?.take(220)
internal fun parsePcInfo(body: String, fallbackPcId: String = "main", code: Int? = null, elapsed: Long? = null): RelayResult<PcInfo> = runCatching {
    val j = JSONObject(body); val stateText = j.optString("state", j.optString("status", "unknown")); val state = PcState.entries.firstOrNull { it.name.equals(stateText, true) } ?: PcState.UNKNOWN
    val agent = j.optJSONObject("agent"); val sunshine = j.optJSONObject("sunshine"); val tailscale = j.optJSONObject("tailscale"); val caps = j.optJSONObject("capabilities")
    fun JSONObject?.bool(name: String): Boolean? = if (this?.has(name) == true && !isNull(name)) optBoolean(name) else null
    PcInfo(j.optString("pcId", fallbackPcId), j.optString("name", "MGGX PC"), state, j.optString("lastSeen").takeIf { it.isNotBlank() && it != "null" }?.let { Instant.parse(it).toEpochMilli() }, AgentStatus(agent.bool("reachable"), agent?.optString("version")?.takeIf(String::isNotBlank), agent?.takeIf { it.has("uptimeSeconds") && !it.isNull("uptimeSeconds") }?.optLong("uptimeSeconds")), ServiceStatus(sunshine.bool("installed"), sunshine.bool("running")), PcTailscaleStatus(tailscale.bool("installed"), tailscale.bool("running"), tailscale?.optString("ip")?.takeIf(String::isNotBlank)), PcCapabilities(caps.bool("wake"), caps.bool("shutdown"), caps.bool("restart"), caps.bool("sleep"), caps.bool("hibernate"), caps.bool("lock"), caps.bool("sunshineRestart")), j.optString("message").takeIf(String::isNotBlank))
}.fold({ RelayResult.Success(it, code, elapsed) }, { RelayResult.Failure(RelayError.InvalidResponse, RelayTechnical("STATUS_PARSE", httpCode = code, exceptionType = it.javaClass.simpleName, message = sanitize(it.message), elapsedMs = elapsed)) })
private inline fun <T, R> RelayResult<T>.flatMap(block: (T, Int?, Long?) -> RelayResult<R>): RelayResult<R> = when (this) { is RelayResult.Success -> block(value, httpCode, elapsedMs); is RelayResult.Failure -> this }
internal suspend fun tcpProbe(endpoint: RelayEndpoint, timeoutMs: Int): RelayResult<Long> = withContext(Dispatchers.IO) { val started = System.nanoTime(); try { Socket().use { it.connect(java.net.InetSocketAddress(endpoint.host, endpoint.port), timeoutMs) }; RelayResult.Success(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)) } catch (e: Throwable) { RelayResult.Failure(classifyThrowable(e), RelayTechnical("TCP_CONNECT", endpoint.host, endpoint.port, exceptionType = e.javaClass.simpleName, message = sanitize(e.message), elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))) } }
