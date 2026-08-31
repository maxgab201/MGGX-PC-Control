package com.mggx.pccontrol.next.pairing

import com.mggx.pccontrol.next.v2.DeviceRole
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val PAIRING_PROTOCOL_VERSION = 1

data class PairingOffer(
    val host: String,
    val port: Int,
    val secret: String,
    val expiresAtEpochMs: Long,
    val role: DeviceRole,
    val protocolVersion: Int = PAIRING_PROTOCOL_VERSION,
) {
    fun qrUri(): String = "mggx://pair/v$protocolVersion?host=${host.encode()}&port=$port&secret=${secret.encode()}&expires=$expiresAtEpochMs&role=${role.name.lowercase()}"
    fun humanCode(): String = pairingCode(secret)
}

sealed interface PairingParseResult {
    data class Valid(val offer: PairingOffer) : PairingParseResult
    data class Invalid(val reason: String) : PairingParseResult
}

/**
 * QR payloads contain a 256-bit single-use secret. The six digit code is only a human aid and
 * is never accepted as the sole credential.
 */
object PairingProtocol {
    private val random = SecureRandom()

    fun createOffer(host: String, port: Int, role: DeviceRole, nowMs: Long = System.currentTimeMillis(), lifetimeMs: Long = 10 * 60_000L): PairingOffer {
        require(host.isNotBlank() && port in 1..65_535)
        val bytes = ByteArray(32).also(random::nextBytes)
        return PairingOffer(host.trim(), port, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), nowMs + lifetimeMs, role)
    }

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): PairingParseResult = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme == "mggx" && uri.host == "pair") { "Código no reconocido" }
        val version = uri.path.removePrefix("/v").toIntOrNull() ?: error("Versión inválida")
        require(version == PAIRING_PROTOCOL_VERSION) { "Esta versión de MGGX PC Control no es compatible" }
        val values = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (key, value) = it.split('=', limit = 2)
            key to java.net.URLDecoder.decode(value, Charsets.UTF_8)
        }
        val host = values["host"].orEmpty().trim()
        val port = values["port"]?.toIntOrNull() ?: error("Puerto inválido")
        val secret = values["secret"].orEmpty()
        val expires = values["expires"]?.toLongOrNull() ?: error("Código inválido")
        val role = runCatching { DeviceRole.valueOf(values["role"].orEmpty().uppercase()) }.getOrElse { error("Función de dispositivo inválida") }
        require(host.isNotBlank() && !host.any { it.isWhitespace() } && port in 1..65_535) { "Dirección inválida" }
        require(secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Código secreto inválido" }
        require(expires > nowMs) { "Este código ya venció" }
        PairingParseResult.Valid(PairingOffer(host, port, secret, expires, role, version))
    }.getOrElse { PairingParseResult.Invalid(it.message ?: "Código inválido") }

    fun constantTimeEquals(expected: String, received: String): Boolean = MessageDigest.isEqual(expected.toByteArray(), received.toByteArray())
}

fun pairingCode(secret: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
    val number = (((digest[0].toInt() and 0xff) shl 16) or ((digest[1].toInt() and 0xff) shl 8) or (digest[2].toInt() and 0xff)) % 1_000_000
    return "%06d".format(number)
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)
