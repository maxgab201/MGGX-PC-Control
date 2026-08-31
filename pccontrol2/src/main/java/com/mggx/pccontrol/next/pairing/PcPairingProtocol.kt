package com.mggx.pccontrol.next.pairing

import java.net.URI

data class PcPairingOffer(
    val host: String,
    val port: Int,
    val secret: String,
    val expiresAtEpochMs: Long,
    val protocolVersion: Int = 1,
)

sealed interface PcPairingParseResult {
    data class Valid(val offer: PcPairingOffer) : PcPairingParseResult
    data class Invalid(val reason: String) : PcPairingParseResult
}

/** Versioned QR contract implemented by the Android side and documented for PC Agent 1.1. */
object PcPairingProtocol {
    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): PcPairingParseResult = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme == "mggx" && uri.host == "pc-agent" && uri.path == "/v1") { "Este código no pertenece a MGGX PC Agent" }
        val values = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (key, value) = it.split('=', limit = 2)
            key to java.net.URLDecoder.decode(value, "UTF-8")
        }
        val host = values["host"].orEmpty().trim()
        val port = values["port"]?.toIntOrNull() ?: error("Puerto inválido")
        val secret = values["secret"].orEmpty()
        val expires = values["expires"]?.toLongOrNull() ?: error("Código inválido")
        require(host.isNotBlank() && !host.any(Char::isWhitespace) && port in 1..65_535) { "Dirección inválida" }
        require(secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Código secreto inválido" }
        require(expires > nowMs) { "Este código ya venció" }
        PcPairingParseResult.Valid(PcPairingOffer(host, port, secret, expires))
    }.getOrElse { PcPairingParseResult.Invalid(it.message ?: "Código inválido") }
}

enum class PairingQrKind { HOME_PHONE, PC_AGENT }

sealed interface ValidatedQr {
    data class Home(val offer: PairingOffer) : ValidatedQr
    data class Pc(val offer: PcPairingOffer) : ValidatedQr
}

fun validatePairingQr(raw: String, kind: PairingQrKind): Result<ValidatedQr> = when (kind) {
    PairingQrKind.HOME_PHONE -> when (val result = PairingProtocol.parse(raw)) {
        is PairingParseResult.Invalid -> Result.failure(IllegalArgumentException(result.reason))
        is PairingParseResult.Valid -> if (result.offer.role == com.mggx.pccontrol.next.v2.DeviceRole.HOME_PHONE) Result.success(ValidatedQr.Home(result.offer)) else Result.failure(IllegalArgumentException("El código corresponde a otra función de dispositivo"))
    }
    PairingQrKind.PC_AGENT -> when (val result = PcPairingProtocol.parse(raw)) {
        is PcPairingParseResult.Invalid -> Result.failure(IllegalArgumentException(result.reason))
        is PcPairingParseResult.Valid -> Result.success(ValidatedQr.Pc(result.offer))
    }
}

