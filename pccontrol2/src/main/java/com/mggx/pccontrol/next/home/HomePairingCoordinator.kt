package com.mggx.pccontrol.next.home

import com.mggx.pccontrol.next.pairing.PairingOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object HomePairingCoordinator {
    val sessions = HomePairingSessions()
    private val _offer = MutableStateFlow<PairingOffer?>(null)
    val offer: StateFlow<PairingOffer?> = _offer.asStateFlow()

    fun generate(port: Int, nowMs: Long = System.currentTimeMillis()): Result<PairingOffer> = runCatching {
        val host = TailscaleAddressProvider.address() ?: error("Tailscale todavía no asignó una dirección a este celular.")
        sessions.create(host, port, nowMs = nowMs).also { _offer.value = it }
    }
    fun clearIfConsumed(secret: String) { if (_offer.value?.secret == secret) _offer.value = null }
}

object TailscaleAddressProvider {
    fun address(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress.orEmpty() }
        .firstOrNull(::isTailnetIpv4)

    fun isTailnetIpv4(value: String): Boolean {
        val parts = value.split('.').mapNotNull(String::toIntOrNull)
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 && parts.all { it in 0..255 }
    }
}
