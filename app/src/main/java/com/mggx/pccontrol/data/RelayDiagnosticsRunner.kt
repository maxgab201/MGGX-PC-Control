package com.mggx.pccontrol.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mggx.pccontrol.domain.ConnectionDiagnostics
import com.mggx.pccontrol.domain.DiagnosticStage
import com.mggx.pccontrol.domain.DiagnosticState
import com.mggx.pccontrol.domain.RelayConfig
import com.mggx.pccontrol.domain.RelayError
import com.mggx.pccontrol.domain.RelayResult
import com.mggx.pccontrol.domain.userMessage

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Executes the real relay path: validate → VPN observation → TCP → health → auth/status. */
class RelayDiagnosticsRunner(private val context: Context?) {
    suspend fun run(config: RelayConfig, onUpdate: (ConnectionDiagnostics) -> Unit = {}): ConnectionDiagnostics {
        var report = ConnectionDiagnostics(tailscaleInstalled = context?.let(::isInstalled) ?: false)
        fun publish() = onUpdate(report)
        report = report.withStep(DiagnosticStage.Url, DiagnosticState.RUNNING); publish()
        val endpoint = when (val normalized = RelayUrlNormalizer.normalize(config.url)) {
            is RelayResult.Success -> normalized.value
            is RelayResult.Failure -> return report.copy(error = normalized.error, technical = normalized.technical)
                .withStep(DiagnosticStage.Url, DiagnosticState.FAILURE, normalized.error.userMessage()).also { onUpdate(it) }
        }
        report = report.copy(endpoint = endpoint).withStep(DiagnosticStage.Url, DiagnosticState.SUCCESS, endpoint.displayUrl); publish()
        val vpn = context?.let(::vpnActive)
        report = report.copy(vpnActive = vpn).withStep(DiagnosticStage.Vpn, DiagnosticState.SUCCESS, when (vpn) { true -> "Activa"; false -> "No activa"; null -> "No disponible" }); publish()

        report = report.withStep(DiagnosticStage.Tcp, DiagnosticState.RUNNING); publish()
        when (val tcp = tcpProbe(endpoint, config.timeoutSeconds.coerceIn(2, 15) * 1_000)) {
            is RelayResult.Success -> { report = report.withStep(DiagnosticStage.Tcp, DiagnosticState.SUCCESS, "${tcp.value} ms"); publish() }
            is RelayResult.Failure -> return report.copy(error = tcp.error, technical = tcp.technical)
                .withStep(DiagnosticStage.Tcp, DiagnosticState.FAILURE, tcp.error.userMessage()).also { onUpdate(it) }
        }

        val api = HttpRelayApi(config)
        report = report.withStep(DiagnosticStage.Health, DiagnosticState.RUNNING); publish()
        when (val health = api.getHealth()) {
            is RelayResult.Success -> { report = report.withStep(DiagnosticStage.Health, DiagnosticState.SUCCESS, "200 · MGGX Relay v${health.value.version}"); publish() }
            is RelayResult.Failure -> return report.copy(error = health.error, technical = health.technical)
                .withStep(DiagnosticStage.Health, DiagnosticState.FAILURE, health.error.userMessage()).also { onUpdate(it) }
        }

        report = report.withStep(DiagnosticStage.Authentication, DiagnosticState.RUNNING); publish()
        when (val status = api.getStatus()) {
            is RelayResult.Success -> {
                report = report.withStep(DiagnosticStage.Authentication, DiagnosticState.SUCCESS, "OK")
                    .withStep(DiagnosticStage.Status, DiagnosticState.SUCCESS, "200 · ${status.value.state.name}")
                    .copy(pcInfo = status.value)
                publish(); return report
            }
            is RelayResult.Failure -> {
                val stage = if (status.error == RelayError.Unauthorized || status.error == RelayError.Forbidden) DiagnosticStage.Authentication else DiagnosticStage.Status
                return report.copy(error = status.error, technical = status.technical)
                    .withStep(stage, DiagnosticState.FAILURE, status.error.userMessage()).also { onUpdate(it) }
            }
        }
    }

    private fun vpnActive(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network -> manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    private fun isInstalled(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0) }.isSuccess
}
