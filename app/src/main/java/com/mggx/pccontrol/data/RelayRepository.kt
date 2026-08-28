package com.mggx.pccontrol.data

import android.util.Log
import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "MggxRelay"

/** Shared validator used by the app, widgets, tiles and connection diagnostics. */
object RelayUrlNormalizer {
    fun normalize(input: String): RelayResult<RelayEndpoint> {
        val trimmed = input.trim()
        if (trimmed.isBlank() || trimmed.any { it == '\u0000' || Character.getType(it) == Character.FORMAT || it.isWhitespace() }) {
            return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_VALIDATE"))
        }
        val candidate = if ("://" in trimmed) trimmed else "${if (isLocalOrTailnet(extractHost(trimmed))) "http" else "https"}://$trimmed"
        val parsed = candidate.toHttpUrlOrNull()
            ?: return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_PARSE"))
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
            return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_COMPONENTS", parsed.host, parsed.port))
        }
        if (parsed.scheme == "http" && !isLocalOrTailnet(parsed.host)) {
            return RelayResult.Failure(RelayError.InsecurePublicHttp, RelayTechnical("URL_POLICY", parsed.host, parsed.port))
        }
        val path = parsed.pathSegments.filter { it.isNotBlank() }
        val acceptedPath = path.isEmpty() || path == listOf("api", "v1") || path == listOf("health")
        if (!acceptedPath) return RelayResult.Failure(RelayError.InvalidUrl, RelayTechnical("URL_BASE_PATH", parsed.host, parsed.port))
        val root = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()
        return RelayResult.Success(RelayEndpoint(root))
    }

    private fun extractHost(value: String): String = when {
        value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
        else -> value.substringBefore('/').substringBefore(':')
    }

    private fun isLocalOrTailnet(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".ts.net") || (!lower.contains('.') && !lower.contains(':'))) return true
        if (lower == "::1" || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")) return true
        val octets = lower.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 100 && octets[1] in 64..127) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}

class DemoRelayApi(
    private val status: () -> DemoStatus,
    private val setStatus: (DemoStatus) -> Unit,
    private val wakeDelayMs: Long = 1_200,
) : PcRelayApi {
    private var currentStatus = status()
    private var activeMonitor = "1"
    override suspend fun getStatus(): RelayResult<PcInfo> {
        if (currentStatus == DemoStatus.ERROR) return RelayResult.Failure(RelayError.Unknown("DEMO_ERROR"))
        return RelayResult.Success(PcInfo("main", "MGGX PC", when (currentStatus) {
            DemoStatus.OFFLINE -> PcState.OFFLINE; DemoStatus.WAKING -> PcState.WAKING
            DemoStatus.ONLINE -> PcState.ONLINE; DemoStatus.ERROR -> PcState.ERROR
        }, System.currentTimeMillis(), listOf(Monitor("1", "Monitor 1", activeMonitor == "1"), Monitor("2", "Monitor 2", activeMonitor == "2"))))
    }
    private fun transition(next: DemoStatus) { currentStatus = next; setStatus(next) }
    override suspend fun wake(): RelayResult<Unit> { transition(DemoStatus.WAKING); delay(wakeDelayMs); transition(DemoStatus.ONLINE); return RelayResult.Success(Unit, 202) }
    override suspend fun shutdown(): RelayResult<Unit> { delay(300); transition(DemoStatus.OFFLINE); return RelayResult.Success(Unit) }
    override suspend fun restart(): RelayResult<Unit> { transition(DemoStatus.WAKING); delay(800); transition(DemoStatus.ONLINE); return RelayResult.Success(Unit) }
    override suspend fun sleep() = shutdown()
    override suspend fun hibernate() = shutdown()
    override suspend fun getMonitors(): RelayResult<List<Monitor>> = when (val state = getStatus()) { is RelayResult.Success -> RelayResult.Success(state.value.monitors); is RelayResult.Failure -> state }
    override suspend fun activateMonitor(id: String): RelayResult<Unit> = if (id in setOf("1", "2")) { activeMonitor = id; RelayResult.Success(Unit) } else RelayResult.Failure(RelayError.Functional(404, "unknown_monitor"))
    override suspend fun action(action: RemoteAction): RelayResult<Unit> = if (currentStatus == DemoStatus.ONLINE) RelayResult.Success(Unit) else RelayResult.Failure(RelayError.Functional(409, "pc_offline"))
}

class HttpRelayApi(
    private val config: RelayConfig,
    private val clientOverride: OkHttpClient? = null,
) : PcRelayApi {
    private val client: OkHttpClient by lazy {
        clientOverride ?: OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds.toLong() + 2, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    fun endpoint(): RelayResult<RelayEndpoint> = RelayUrlNormalizer.normalize(config.url)

    suspend fun getHealth(): RelayResult<RelayHealth> = request("health", authenticated = false).flatMap { body, code, elapsed ->
        runCatching {
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false) || json.optString("service") != "mggx-relay") throw IllegalArgumentException("health payload")
            RelayResult.Success(RelayHealth(json.optInt("version", 0)), code, elapsed)
        }.getOrElse { RelayResult.Failure(RelayError.InvalidResponse, RelayTechnical("HEALTH_PARSE", elapsedMs = elapsed)) }
    }

    override suspend fun getStatus(): RelayResult<PcInfo> = request("api/v1/status").flatMap { body, code, elapsed ->
        parsePcInfo(body, config.pcId, code, elapsed)
    }
    override suspend fun wake() = unit("api/v1/power/wake")
    override suspend fun shutdown() = unit("api/v1/power/shutdown")
    override suspend fun restart() = unit("api/v1/power/restart")
    override suspend fun sleep() = unit("api/v1/power/sleep")
    override suspend fun hibernate() = unit("api/v1/power/hibernate")
    private suspend fun unit(path: String): RelayResult<Unit> = request(path, "POST").flatMap { _, code, elapsed -> RelayResult.Success(Unit, code, elapsed) }
    override suspend fun getMonitors(): RelayResult<List<Monitor>> = request("api/v1/monitors").flatMap { body, code, elapsed ->
        runCatching { RelayResult.Success(parseMonitors(JSONArray(body)), code, elapsed) }.getOrElse { RelayResult.Failure(RelayError.InvalidResponse, RelayTechnical("MONITORS_PARSE", elapsedMs = elapsed)) }
    }
    override suspend fun activateMonitor(id: String) = unit("api/v1/monitors/${id.encodePathSegment()}/activate")
    override suspend fun action(action: RemoteAction) = unit("api/v1/actions/${action.path}")

    private suspend fun request(path: String, method: String = "GET", authenticated: Boolean = true): RelayResult<String> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val endpoint = when (val parsed = endpoint()) {
            is RelayResult.Success -> parsed.value
            is RelayResult.Failure -> return@withContext parsed
        }
        val elapsed: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) }
        try {
            val target = endpoint.url.newBuilder().addPathSegments(path).build()
            val builder = Request.Builder().url(target).method(method, if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType()))
            if (authenticated && config.token.isNotBlank()) builder.header("Authorization", "Bearer ${config.token.trim()}")
            Log.d(TAG, "Relay request: stage=HTTP host=${endpoint.host} port=${endpoint.port} scheme=${endpoint.scheme} path=/${path.substringBefore('?')}")
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) RelayResult.Success(body, response.code, elapsed())
                else RelayResult.Failure(httpError(response.code, body), RelayTechnical("HTTP", endpoint.host, endpoint.port, response.code, elapsedMs = elapsed()))
            }
        } catch (error: IllegalArgumentException) {
            RelayResult.Failure(RelayError.InvalidToken, technical("HTTP_HEADER", endpoint, error, elapsed()))
        } catch (error: Throwable) {
            RelayResult.Failure(classifyThrowable(error), technical("HTTP", endpoint, error, elapsed()))
        }
    }

    private fun technical(stage: String, endpoint: RelayEndpoint, error: Throwable, elapsed: Long) = RelayTechnical(stage, endpoint.host, endpoint.port, exceptionType = error.javaClass.simpleName, message = sanitize(error.message), elapsedMs = elapsed)
}

data class RelayHealth(val version: Int)

private fun String.encodePathSegment(): String = replace("/", "%2F")
private fun httpError(code: Int, body: String): RelayError = when (code) {
    401 -> RelayError.Unauthorized
    403 -> RelayError.Forbidden
    404 -> RelayError.ApiVersionMismatch
    409 -> RelayError.Functional(code, runCatching { JSONObject(body).optString("error").takeIf { it.isNotBlank() } }.getOrNull())
    in 500..599 -> RelayError.Server(code)
    else -> RelayError.Functional(code, runCatching { JSONObject(body).optString("error").takeIf { it.isNotBlank() } }.getOrNull())
}

internal fun classifyThrowable(error: Throwable): RelayError = when (error) {
    is SocketTimeoutException -> RelayError.Timeout
    is UnknownHostException -> RelayError.DnsFailure
    is NoRouteToHostException -> RelayError.NoRoute
    is ConnectException -> if (error.message?.contains("refused", true) == true) RelayError.ConnectionRefused else RelayError.NoRoute
    is SecurityException -> RelayError.NetworkPermissionDenied
    else -> if (error.message?.contains("EPERM", true) == true || error.message?.contains("permission denied", true) == true) RelayError.NetworkPermissionDenied else RelayError.Unknown(error.javaClass.simpleName)
}
internal fun sanitize(message: String?): String? = message?.replace(Regex("[\\r\\n]+"), " ")?.take(220)

internal fun parsePcInfo(body: String, fallbackPcId: String = "main", code: Int? = null, elapsed: Long? = null): RelayResult<PcInfo> = runCatching {
    val j = JSONObject(body)
    val rawState = j.optString("state", j.optString("status", "unknown"))
    val state = PcState.entries.firstOrNull { it.name.equals(rawState, true) } ?: PcState.UNKNOWN
    val monitors = j.optJSONArray("monitors")?.let(::parseMonitors).orEmpty()
    RelayResult.Success(PcInfo(j.optString("pcId", fallbackPcId), j.optString("name", "MGGX PC"), state, j.optString("lastSeen").takeIf { it.isNotBlank() && it != "null" }?.let { java.time.Instant.parse(it).toEpochMilli() }, monitors, j.optString("message").takeIf { it.isNotBlank() }), code, elapsed)
}.getOrElse { RelayResult.Failure(RelayError.InvalidResponse, RelayTechnical("STATUS_PARSE", httpCode = code, exceptionType = it.javaClass.simpleName, message = sanitize(it.message), elapsedMs = elapsed)) }

internal fun parseMonitors(array: JSONArray): List<Monitor> = (0 until array.length()).map { index -> array.getJSONObject(index) }.map { Monitor(it.getString("id"), it.optString("name", "Monitor"), it.optBoolean("active", false)) }

private inline fun <T, R> RelayResult<T>.flatMap(block: (T, Int?, Long?) -> RelayResult<R>): RelayResult<R> = when (this) {
    is RelayResult.Success -> block(value, httpCode, elapsedMs)
    is RelayResult.Failure -> this
}

internal suspend fun tcpProbe(endpoint: RelayEndpoint, timeoutMs: Int): RelayResult<Long> = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    try {
        Socket().use { it.connect(java.net.InetSocketAddress(endpoint.host, endpoint.port), timeoutMs) }
        RelayResult.Success(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
    } catch (error: Throwable) {
        RelayResult.Failure(classifyThrowable(error), RelayTechnical("TCP_CONNECT", endpoint.host, endpoint.port, exceptionType = error.javaClass.simpleName, message = sanitize(error.message), elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)))
    }
}
