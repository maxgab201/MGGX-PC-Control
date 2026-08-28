package com.mggx.pccontrol.domain

import okhttp3.HttpUrl

enum class PcState { UNKNOWN, OFFLINE, WAKING, ONLINE, CONNECTING, ERROR }
enum class DemoStatus { OFFLINE, WAKING, ONLINE, ERROR }
enum class PowerAction { WAKE, SHUTDOWN, RESTART, SLEEP, HIBERNATE }
enum class RemoteAction(val path: String) { CAMERA("camera"), TERMINAL("terminal"), FILES("files"), ADMIN("admin"), TASK_MANAGER("task-manager"), LOCK("lock") }

data class Monitor(val id: String, val name: String, val active: Boolean = false)
data class PcInfo(val pcId: String, val name: String, val state: PcState, val lastSeen: Long? = null, val monitors: List<Monitor> = emptyList(), val message: String? = null)
data class RelayConfig(val url: String = "", val token: String = "", val pcId: String = "main", val timeoutSeconds: Int = 8)

/** Normalized endpoint. Token is deliberately not part of this object. */
data class RelayEndpoint(val url: HttpUrl) {
    val scheme get() = url.scheme
    val host get() = url.host
    val port get() = url.port
    val displayUrl get() = url.toString().removeSuffix("/")
}

sealed interface RelayError {
    data object InvalidUrl : RelayError
    data object InsecurePublicHttp : RelayError
    data object InvalidToken : RelayError
    data object TokenUnreadable : RelayError
    data object DnsFailure : RelayError
    data object ConnectionRefused : RelayError
    data object NoRoute : RelayError
    data object Timeout : RelayError
    data object NetworkPermissionDenied : RelayError
    data object Unauthorized : RelayError
    data object Forbidden : RelayError
    data object ApiVersionMismatch : RelayError
    data class Functional(val code: Int, val relayError: String?) : RelayError
    data class Server(val code: Int) : RelayError
    data object InvalidResponse : RelayError
    data class Unknown(val technicalType: String) : RelayError
}

fun RelayError.userMessage(): String = when (this) {
    RelayError.InvalidUrl -> "URL del relay inválida. Revisá esquema, host y puerto."
    RelayError.InsecurePublicHttp -> "HTTP solo se permite para direcciones privadas o Tailscale. Usá HTTPS para un host público."
    RelayError.InvalidToken -> "El API token no es válido para construir el encabezado Bearer."
    RelayError.TokenUnreadable -> "Token guardado no pudo ser leído. Volvé a ingresar el token."
    RelayError.DnsFailure -> "No se pudo resolver el host del relay o MagicDNS."
    RelayError.ConnectionRefused -> "El relay respondió, pero rechazó la conexión en ese puerto."
    RelayError.NoRoute -> "No hay ruta de red hacia el relay."
    RelayError.Timeout -> "Tiempo de conexión agotado."
    RelayError.NetworkPermissionDenied -> "Android bloqueó el acceso de red de la app."
    RelayError.Unauthorized -> "Relay accesible. Autenticación fallida: revisá el API token."
    RelayError.Forbidden -> "Relay accesible, pero la acción no está autorizada."
    RelayError.ApiVersionMismatch -> "Relay accesible, pero el endpoint o la versión de API no coincide."
    is RelayError.Functional -> "El relay respondió un error funcional${relayError?.let { ": $it" }.orEmpty()}."
    is RelayError.Server -> "Relay accesible, pero respondió un error del servidor (${code})."
    RelayError.InvalidResponse -> "Relay accesible, pero la respuesta no tiene el formato esperado."
    is RelayError.Unknown -> "No se pudo completar la solicitud al relay."
}

data class RelayTechnical(
    val stage: String,
    val host: String? = null,
    val port: Int? = null,
    val httpCode: Int? = null,
    val exceptionType: String? = null,
    val message: String? = null,
    val elapsedMs: Long? = null,
)

sealed class RelayResult<out T> {
    data class Success<T>(val value: T, val httpCode: Int? = null, val elapsedMs: Long? = null) : RelayResult<T>()
    data class Failure(val error: RelayError, val technical: RelayTechnical? = null) : RelayResult<Nothing>() {
        val userMessage: String get() = error.userMessage()
    }
}

sealed interface DiagnosticStage { val label: String
    data object Url : DiagnosticStage { override val label = "URL válida" }
    data object Vpn : DiagnosticStage { override val label = "VPN" }
    data object Tcp : DiagnosticStage { override val label = "TCP" }
    data object Health : DiagnosticStage { override val label = "/health" }
    data object Authentication : DiagnosticStage { override val label = "Autenticación" }
    data object Status : DiagnosticStage { override val label = "Estado de PC" }
}
enum class DiagnosticState { PENDING, RUNNING, SUCCESS, FAILURE, SKIPPED }
data class DiagnosticStep(val stage: DiagnosticStage, val state: DiagnosticState, val detail: String = "")
data class ConnectionDiagnostics(
    val endpoint: RelayEndpoint? = null,
    val vpnActive: Boolean? = null,
    val tailscaleInstalled: Boolean = false,
    val steps: List<DiagnosticStep> = listOf(DiagnosticStage.Url, DiagnosticStage.Vpn, DiagnosticStage.Tcp, DiagnosticStage.Health, DiagnosticStage.Authentication, DiagnosticStage.Status).map { DiagnosticStep(it, DiagnosticState.PENDING) },
    val pcInfo: PcInfo? = null,
    val error: RelayError? = null,
    val technical: RelayTechnical? = null,
) {
    fun withStep(stage: DiagnosticStage, state: DiagnosticState, detail: String = "") = copy(steps = steps.map { if (it.stage == stage) DiagnosticStep(stage, state, detail) else it })
}

interface PcRelayApi {
    suspend fun getStatus(): RelayResult<PcInfo>
    suspend fun wake(): RelayResult<Unit>
    suspend fun shutdown(): RelayResult<Unit>
    suspend fun restart(): RelayResult<Unit>
    suspend fun sleep(): RelayResult<Unit>
    suspend fun hibernate(): RelayResult<Unit>
    suspend fun getPcInfo(): RelayResult<PcInfo> = getStatus()
    suspend fun getMonitors(): RelayResult<List<Monitor>>
    suspend fun activateMonitor(id: String): RelayResult<Unit>
    suspend fun action(action: RemoteAction): RelayResult<Unit>
}
