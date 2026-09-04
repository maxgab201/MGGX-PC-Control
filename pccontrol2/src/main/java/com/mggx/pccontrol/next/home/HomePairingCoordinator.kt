package com.mggx.pccontrol.next.home

import com.mggx.pccontrol.next.pairing.PairingOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

enum class HomeOfferPhase { EMPTY, ACTIVE, EXPIRED, CONSUMED, ERROR }

data class HomeOfferState(
    val phase: HomeOfferPhase = HomeOfferPhase.EMPTY,
    val offer: PairingOffer? = null,
    val message: String? = null,
)

data class OfferCountdown(val remainingSeconds: Long, val expired: Boolean)

fun offerCountdown(expiresAtEpochMs: Long, nowMs: Long): OfferCountdown {
    val remainingMs = expiresAtEpochMs - nowMs
    return if (remainingMs <= 0L) OfferCountdown(0L, true)
    else OfferCountdown((remainingMs + 999L) / 1_000L, false)
}

object HomePairingCoordinator {
    val sessions = HomePairingSessions()
    private val lock = Any()
    private val _state = MutableStateFlow(HomeOfferState())
    val state: StateFlow<HomeOfferState> = _state.asStateFlow()

    fun ensure(
        port: Int,
        nowMs: Long = System.currentTimeMillis(),
        addressProvider: () -> String? = TailscaleAddressProvider::address,
    ): Result<PairingOffer> = synchronized(lock) {
        val current = _state.value.offer
        if (_state.value.phase == HomeOfferPhase.ACTIVE && current != null && current.expiresAtEpochMs > nowMs) {
            Result.success(current)
        } else generateLocked(port, nowMs, addressProvider)
    }

    fun generate(
        port: Int,
        nowMs: Long = System.currentTimeMillis(),
        addressProvider: () -> String? = TailscaleAddressProvider::address,
    ): Result<PairingOffer> = synchronized(lock) {
        generateLocked(port, nowMs, addressProvider)
    }

    private fun generateLocked(port: Int, nowMs: Long, addressProvider: () -> String?): Result<PairingOffer> = runCatching {
        val host = addressProvider()
            ?: error("Tailscale todavía no asignó una IP a este celular. Abrí Tailscale, conectalo y volvé a intentar.")
        sessions.create(host, port, nowMs = nowMs).also {
            _state.value = HomeOfferState(HomeOfferPhase.ACTIVE, it)
        }
    }.onFailure {
        _state.value = HomeOfferState(HomeOfferPhase.ERROR, message = it.message ?: "No se pudo generar el código de vinculación.")
    }

    fun markExpired(secret: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = _state.value.offer
        if (current?.secret == secret && current.expiresAtEpochMs <= nowMs) {
            _state.value = HomeOfferState(HomeOfferPhase.EXPIRED, current, "El código venció. Generá uno nuevo.")
        }
    }

    fun clearIfConsumed(secret: String) = synchronized(lock) {
        if (_state.value.offer?.secret == secret) {
            _state.value = HomeOfferState(HomeOfferPhase.CONSUMED, message = "El celular principal quedó vinculado ✓")
        }
    }

    /** A QR must never outlive the listener that can redeem it. */
    fun invalidateForUnavailableServer(message: String) = synchronized(lock) {
        sessions.clear()
        _state.value = HomeOfferState(HomeOfferPhase.ERROR, message = message)
    }

    internal fun resetForTest() = synchronized(lock) { _state.value = HomeOfferState() }
}

object TailscaleAddressProvider {
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull(::isTailnetIpv4)
    }.getOrNull()

    fun isTailnetIpv4(value: String): Boolean {
        val parts = value.split('.').mapNotNull(String::toIntOrNull)
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 && parts.all { it in 0..255 }
    }
}
