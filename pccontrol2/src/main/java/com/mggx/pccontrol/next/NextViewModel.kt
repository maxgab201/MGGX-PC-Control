package com.mggx.pccontrol.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mggx.pccontrol.next.data.HttpRelayApi
import com.mggx.pccontrol.next.data.NextSettings
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.domain.PcInfo
import com.mggx.pccontrol.next.domain.PcState
import com.mggx.pccontrol.next.domain.PowerAction
import com.mggx.pccontrol.next.domain.RelayConfig
import com.mggx.pccontrol.next.domain.RelayResult
import com.mggx.pccontrol.next.home.HomeClaimResult
import com.mggx.pccontrol.next.home.HomeDeviceRuntime
import com.mggx.pccontrol.next.home.HomeDeviceService
import com.mggx.pccontrol.next.home.HomePairingClient
import com.mggx.pccontrol.next.home.HomePairingCoordinator
import com.mggx.pccontrol.next.home.HomeRestoreWorker
import com.mggx.pccontrol.next.pairing.PairingOffer
import com.mggx.pccontrol.next.pairing.PcAgentPairingClient
import com.mggx.pccontrol.next.pairing.PcPairingOffer
import com.mggx.pccontrol.next.pairing.PcPairingResult
import com.mggx.pccontrol.next.security.CredentialResult
import com.mggx.pccontrol.next.v2.CheckState
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.HomeServerState
import com.mggx.pccontrol.next.v2.OnboardingStep
import com.mggx.pccontrol.next.v2.VerificationItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface UiEvent { data object OpenMoonlight : UiEvent; data class Message(val text: String) : UiEvent }

class NextViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NextSettingsStore(application)
    private val pairingClient = HomePairingClient(store)
    private val pcPairingClient = PcAgentPairingClient(store)
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NextSettings())
    val homeRuntime = HomeDeviceRuntime.state
    val homeOfferState = HomePairingCoordinator.state
    private val _busy = MutableStateFlow<String?>(null); val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null); val message = _message.asStateFlow()
    private val _pcInfo = MutableStateFlow<PcInfo?>(null); val pcInfo = _pcInfo.asStateFlow()
    private val _verification = MutableStateFlow(defaultChecks()); val verification = _verification.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(); val events = _events.asSharedFlow()

    fun choose(role: DeviceRole) = viewModelScope.launch { store.selectRole(role) }
    fun next(step: OnboardingStep) = viewModelScope.launch { store.setStep(step) }
    fun resume(step: OnboardingStep) = viewModelScope.launch { store.resumeSetup(step) }
    fun complete() = viewModelScope.launch { store.complete() }
    fun clearMessage() { _message.value = null }
    fun startHome() = viewModelScope.launch {
        val current = store.snapshot()
        store.saveHome(current.home.copy(enabled = true))
        ensureHomeServiceReady()
    }
    fun ensureHomeServiceRunning() = viewModelScope.launch { ensureHomeServiceReady() }
    fun restartHome() = viewModelScope.launch {
        _message.value = "Reiniciando la conexión…"
        runCatching { HomeDeviceService.restart(getApplication()) }
            .onFailure { _message.value = "Android no pudo iniciar la conexión: ${it.javaClass.simpleName}" }
    }
    fun ensureHomeOffer() = createHomeOffer(force = false)
    fun generateHomeOffer() = createHomeOffer(force = true)
    private fun createHomeOffer(force: Boolean) = viewModelScope.launch {
        val readiness = ensureHomeServiceReady()
        if (readiness.isFailure) {
            val message = readiness.exceptionOrNull()?.message ?: "El servidor local no está listo."
            HomePairingCoordinator.invalidateForUnavailableServer(message)
            _message.value = message
            return@launch
        }
        val port = store.snapshot().home.port
        val result = withContext(Dispatchers.IO) {
            if (force) HomePairingCoordinator.generate(port) else HomePairingCoordinator.ensure(port)
        }
        _message.value = result.exceptionOrNull()?.message
    }
    fun markHomeOfferExpired(secret: String) = HomePairingCoordinator.markExpired(secret)

    private suspend fun ensureHomeServiceReady(): Result<Unit> {
        val current = store.snapshot()
        if (!current.home.enabled) return Result.failure(IllegalStateException("Primero vinculá la PC para activar la conexión de casa."))
        val started = runCatching { HomeDeviceService.start(getApplication<Application>()) }
        if (started.isFailure) {
            HomeRestoreWorker.enqueue(getApplication())
            return Result.failure(IllegalStateException("Android no pudo iniciar el servicio de conexión."))
        }
        val runtime = withTimeoutOrNull(8_000) {
            HomeDeviceRuntime.state.first { it.serverState == HomeServerState.READY || it.serverState == HomeServerState.ERROR }
        }
        return when {
            runtime?.serverState == HomeServerState.READY && runtime.localHealth -> Result.success(Unit)
            runtime?.lastError != null -> Result.failure(IllegalStateException(runtime.lastError))
            else -> Result.failure(IllegalStateException("El servidor local no respondió. Tocá Reintentar conexión."))
        }
    }

    fun claimHome(offer: PairingOffer) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "home_pair"
        when (val result = pairingClient.claim(offer)) {
            is HomeClaimResult.Success -> { store.setStep(OnboardingStep.CONTROL_SUNSHINE); _message.value = "Celular en casa vinculado a ${result.pcName} ✓" }
            is HomeClaimResult.Failure -> _message.value = result.message
        }
        _busy.value = null
    }

    fun pairPc(offer: PcPairingOffer) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "pc_pair"
        handlePcPairing(pcPairingClient.claim(offer))
        _busy.value = null
    }

    fun configurePcLegacy(url: String, token: String, mac: String, broadcast: String) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "pc_pair"
        handlePcPairing(pcPairingClient.configureLegacy(url, token, mac, broadcast))
        _busy.value = null
    }

    private suspend fun handlePcPairing(result: PcPairingResult) = when (result) {
        is PcPairingResult.Success -> {
            store.setStep(stepAfterPcPairing(result))
            startHomeServiceOrScheduleRestore()
            _message.value = "PC vinculada y comprobada ✓"
        }
        is PcPairingResult.AgentUpgradeRequired -> _message.value = result.message
        is PcPairingResult.Failure -> _message.value = result.message
    }

    fun saveLegacy(url: String, pcId: String, token: String) = viewModelScope.launch { _message.value = if (store.saveLegacy(url, pcId, token)) "Configuración guardada" else "No se pudo guardar" }

    private suspend fun api(): HttpRelayApi? {
        val s = store.snapshot()
        val pairedToken = store.readPairedHomeToken()
        if (s.pairedHomeHost.isNotBlank() && pairedToken is CredentialResult.Value) return HttpRelayApi(RelayConfig("http://${s.pairedHomeHost}:${s.pairedHomePort}", pairedToken.value, "main"))
        val legacyToken = store.readLegacyToken()
        if (s.legacyUrl.isNotBlank() && legacyToken is CredentialResult.Value) return HttpRelayApi(RelayConfig(s.legacyUrl, legacyToken.value, s.legacyPcId))
        return null
    }

    fun refresh() = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "refresh"
        when (val result = api()?.getStatus()) {
            is RelayResult.Success -> { _pcInfo.value = result.value; _message.value = null }
            is RelayResult.Failure -> _message.value = result.userMessage
            null -> _message.value = "Primero vinculá el celular que queda en casa."
        }
        _busy.value = null
    }

    fun command(action: PowerAction) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        val client = api() ?: run { _message.value = "Primero vinculá el celular que queda en casa."; return@launch }
        _busy.value = action.name
        val result = when (action) {
            PowerAction.WAKE -> client.wake(); PowerAction.SHUTDOWN -> client.shutdown(); PowerAction.RESTART -> client.restart()
            PowerAction.SLEEP -> client.sleep(); PowerAction.HIBERNATE -> client.hibernate(); PowerAction.LOCK -> client.lock()
        }
        if (result is RelayResult.Success) { _message.value = "Orden enviada ✓"; delay(700); refreshAfterCommand(client) }
        else if (result is RelayResult.Failure) _message.value = result.userMessage
        _busy.value = null
    }

    fun restartSunshine() = viewModelScope.launch {
        if (_busy.value != null) return@launch
        val client = api() ?: return@launch
        _busy.value = "sunshine"
        _message.value = when (val result = client.restartSunshine()) { is RelayResult.Success -> "Sunshine se está reiniciando ✓"; is RelayResult.Failure -> result.userMessage }
        _busy.value = null
    }

    fun openPc() = viewModelScope.launch {
        if (_busy.value != null) return@launch
        val client = api() ?: run { _message.value = "Primero vinculá el celular que queda en casa."; return@launch }
        _busy.value = "open_pc"
        var info = (client.getStatus() as? RelayResult.Success)?.value
        if (info?.state != PcState.ONLINE) {
            when (val wake = client.wake()) { is RelayResult.Failure -> { _message.value = wake.userMessage; _busy.value = null; return@launch }; else -> _message.value = "Orden de encendido enviada. Esperando Windows…" }
            var attempts = 0
            while (attempts++ < 60 && info?.state != PcState.ONLINE) { delay(2_000); info = (client.getStatus() as? RelayResult.Success)?.value; _pcInfo.value = info }
        }
        if (info?.state != PcState.ONLINE) { _message.value = "La PC no apareció online dentro del tiempo esperado."; _busy.value = null; return@launch }
        if (info?.sunshine?.running != true && info?.capabilities?.sunshineRestart == true) { client.restartSunshine(); var attempts = 0; while (attempts++ < 8 && info?.sunshine?.running != true) { delay(2_000); info = (client.getStatus() as? RelayResult.Success)?.value } }
        if (info?.sunshine?.running == false) _message.value = "Windows está online, pero Sunshine no inició."
        else _events.emit(UiEvent.OpenMoonlight)
        _busy.value = null
    }

    private suspend fun refreshAfterCommand(client: HttpRelayApi) { delay(800); (client.getStatus() as? RelayResult.Success)?.value?.let { _pcInfo.value = it } }

    fun runVerification(tailscaleInstalled: Boolean, vpnActive: Boolean) = viewModelScope.launch {
        _verification.value = defaultChecks()
        fun set(id: String, state: CheckState, detail: String = "") { _verification.value = _verification.value.map { if (it.id == id) it.copy(state = state, detail = detail) else it } }
        set("tailscale", CheckState.RUNNING); set("tailscale", if (tailscaleInstalled && vpnActive) CheckState.SUCCESS else CheckState.FAILURE, if (!tailscaleInstalled) "Instalá Tailscale" else if (!vpnActive) "Activá la VPN" else "")
        val client = api() ?: run { set("home", CheckState.FAILURE, "Falta vincular el celular de casa"); return@launch }
        set("home", CheckState.RUNNING)
        val health = client.getHealth()
        if (health !is RelayResult.Success) { set("home", CheckState.FAILURE, (health as RelayResult.Failure).userMessage); return@launch }
        set("home", CheckState.SUCCESS)
        set("auth", CheckState.RUNNING)
        when (val status = client.getStatus()) {
            is RelayResult.Failure -> { set("auth", CheckState.FAILURE, status.userMessage); return@launch }
            is RelayResult.Success -> {
                set("auth", CheckState.SUCCESS); _pcInfo.value = status.value
                set("agent", if (status.value.agent.reachable == true || status.value.state == PcState.OFFLINE) CheckState.SUCCESS else CheckState.FAILURE, if (status.value.state == PcState.OFFLINE) "PC apagada" else "Agent no responde")
                set("sunshine", if (status.value.sunshine.running == true) CheckState.SUCCESS else CheckState.FAILURE, if (status.value.sunshine.running == false) "Sunshine está detenido" else "Estado no disponible")
                set("tailscale_pc", if (status.value.tailscale.running == true) CheckState.SUCCESS else CheckState.FAILURE, if (status.value.tailscale.running == false) "Tailscale PC está detenido" else "Estado no disponible")
                set("wake", if (status.value.capabilities.wake != false) CheckState.SUCCESS else CheckState.FAILURE, if (status.value.capabilities.wake == false) "Encendido remoto no configurado" else "")
            }
        }
    }

    private fun startHomeServiceOrScheduleRestore() = viewModelScope.launch { ensureHomeServiceReady() }

    companion object {
        fun stepAfterPcPairing(result: PcPairingResult): OnboardingStep =
            if (result is PcPairingResult.Success) OnboardingStep.HOME_PAIR_CONTROL else OnboardingStep.HOME_PAIR_PC

        fun defaultChecks() = listOf(
            VerificationItem("tailscale", "Tailscale de este celular", CheckState.PENDING), VerificationItem("home", "Celular en casa", CheckState.PENDING),
            VerificationItem("auth", "Conexión segura", CheckState.PENDING), VerificationItem("agent", "MGGX PC Agent", CheckState.PENDING),
            VerificationItem("sunshine", "Sunshine", CheckState.PENDING), VerificationItem("tailscale_pc", "Tailscale PC", CheckState.PENDING),
            VerificationItem("wake", "Encendido remoto", CheckState.PENDING),
        )
    }
}
