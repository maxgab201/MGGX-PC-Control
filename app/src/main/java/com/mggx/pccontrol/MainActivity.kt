package com.mggx.pccontrol

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mggx.pccontrol.data.*
import com.mggx.pccontrol.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MOONLIGHT = "com.limelight"
private const val TAILSCALE = "com.tailscale.ipn"

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) { installSplashScreen(); super.onCreate(state); enableEdgeToEdge(); setContent { MggxRoot() } }
}

sealed interface ActionState { data object Idle : ActionState; data object Loading : ActionState; data class Success(val label: String) : ActionState; data class Error(val text: String) : ActionState }
data class PcUiState(
    val loaded: Boolean = false, val settings: AppSettings = AppSettings(), val info: PcInfo = PcInfo("main", "MGGX PC", PcState.UNKNOWN),
    val relayError: RelayError? = null, val technical: RelayTechnical? = null, val latency: Long? = null, val diagnostics: ConnectionDiagnostics? = null,
    val save: ActionState = ActionState.Idle, val test: ActionState = ActionState.Idle, val open: ActionState = ActionState.Idle, val power: ActionState = ActionState.Idle,
    val elapsed: Long = 0, val snackbar: String? = null,
)

class PcViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _ui = MutableStateFlow(PcUiState())
    val ui: StateFlow<PcUiState> = _ui.asStateFlow()
    private var api: PcRelayApi? = null
    private var config: RelayConfig? = null
    private var flowJob: Job? = null

    init { viewModelScope.launch { store.settings.collect { settings ->
        if (settings.demoMode) { api = DemoRelayApi(status = { settings.simulatedStatus }, setStatus = { next -> viewModelScope.launch { store.setDemoStatus(next) } }); config = null }
        else when (val loaded = store.loadRelayConfig(settings)) { is RelayResult.Success -> { config = loaded.value; api = HttpRelayApi(loaded.value) }; is RelayResult.Failure -> { config = null; api = null; _ui.update { it.copy(relayError = loaded.error, technical = loaded.technical) } } }
        _ui.update { old -> old.copy(loaded = true, settings = settings, info = old.info.copy(pcId = settings.pcId, name = settings.pcName)) }
    } } }

    fun finishOnboarding(demo: Boolean) = viewModelScope.launch { store.completeOnboarding(demo) }
    fun setDemo(enabled: Boolean) = viewModelScope.launch { store.setDemo(enabled) }
    fun setDemoStatus(value: DemoStatus) = viewModelScope.launch { store.setDemoStatus(value) }
    fun setAutoOpen(value: Boolean) = viewModelScope.launch { store.setAutoOpen(value) }
    fun clearSnackbar() = _ui.update { it.copy(snackbar = null) }

    fun refresh() = viewModelScope.launch { refreshNow() }
    private suspend fun refreshNow(): PcInfo? {
        val source = api ?: return null
        return when (val result = source.getStatus()) {
            is RelayResult.Success -> { _ui.update { it.copy(info = result.value, relayError = null, technical = null, latency = result.elapsedMs) }; result.value }
            is RelayResult.Failure -> { _ui.update { it.copy(relayError = result.error, technical = result.technical, latency = null) }; null }
        }
    }

    fun saveRelay(url: String, token: String?, pcId: String, timeout: Int, testAfter: Boolean = false) {
        if (_ui.value.save is ActionState.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(save = ActionState.Loading) }
            when (val saved = store.saveRelayAndCreateConfig(url, token, pcId, timeout)) {
                is RelayResult.Failure -> _ui.update { it.copy(save = ActionState.Error(saved.userMessage), snackbar = "No se pudo guardar") }
                is RelayResult.Success -> {
                    config = saved.value.config; api = HttpRelayApi(saved.value.config)
                    _ui.update { it.copy(save = ActionState.Success("GUARDADO"), snackbar = "Configuración guardada") }
                    delay(1_200); _ui.update { if (it.save is ActionState.Success) it.copy(save = ActionState.Idle) else it }
                    if (testAfter) runDiagnostics()
                }
            }
        }
    }

    fun runDiagnostics() {
        if (_ui.value.test is ActionState.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(test = ActionState.Loading) }
            val current = config ?: when (val loaded = store.loadRelayConfig(_ui.value.settings)) { is RelayResult.Success -> loaded.value; is RelayResult.Failure -> { _ui.update { it.copy(test = ActionState.Error(loaded.userMessage), relayError = loaded.error) }; return@launch } }
            val report = RelayDiagnosticsRunner(getApplication()).run(current) { progress -> _ui.update { it.copy(diagnostics = progress) } }
            if (report.error == null) _ui.update { it.copy(test = ActionState.Success("CONECTADO"), info = report.pcInfo ?: it.info, relayError = null, technical = null, snackbar = "Relay conectado") }
            else _ui.update { it.copy(test = ActionState.Error(report.error.userMessage()), relayError = report.error, technical = report.technical, snackbar = report.error.userMessage()) }
        }
    }

    fun openPc(launchMoonlight: () -> Unit) {
        if (flowJob?.isActive == true || _ui.value.open is ActionState.Loading || _ui.value.power is ActionState.Loading) return
        flowJob = viewModelScope.launch {
            _ui.update { it.copy(open = ActionState.Loading, elapsed = 0, snackbar = "Preparando conexión…") }
            val source = api ?: run { _ui.update { it.copy(open = ActionState.Error("Configurá el relay")) }; return@launch }
            when (val outcome = PcFlowCoordinator(source).openPc { pc, elapsed -> _ui.update { it.copy(info = pc, elapsed = elapsed) } }) {
                OpenPcOutcome.MoonlightReady -> { _ui.update { it.copy(open = ActionState.Success("ABRIENDO PC"), snackbar = "Windows y Sunshine están listos") }; launchMoonlight() }
                is OpenPcOutcome.SunshineUnavailable -> _ui.update { it.copy(info = outcome.pc, open = ActionState.Error("Windows está online, pero Sunshine no inició.")) }
                is OpenPcOutcome.Failed -> _ui.update { it.copy(open = ActionState.Error(outcome.error.userMessage()), relayError = outcome.error) }
                OpenPcOutcome.TimedOut -> _ui.update { it.copy(open = ActionState.Error("Se agotó el tiempo esperando Windows o Sunshine.")) }
            }
            delay(1_200); _ui.update { if (it.open is ActionState.Success) it.copy(open = ActionState.Idle) else it }
        }
    }
    fun wakeOnly() = openPc { }
    fun cancelFlow() { flowJob?.cancel(); _ui.update { it.copy(open = ActionState.Idle) } }
    fun restartSunshine() = viewModelScope.launch {
        if (_ui.value.power is ActionState.Loading) return@launch; _ui.update { it.copy(power = ActionState.Loading) }
        when (val result = api?.restartSunshine() ?: RelayResult.Failure(RelayError.InvalidUrl)) { is RelayResult.Success -> { _ui.update { it.copy(power = ActionState.Success("ORDEN ENVIADA"), snackbar = "Reinicio de Sunshine aceptado") }; delay(1_500); refreshNow() }; is RelayResult.Failure -> _ui.update { it.copy(power = ActionState.Error(result.userMessage), relayError = result.error) } }
    }
    fun power(action: PowerAction) {
        if (_ui.value.power is ActionState.Loading || flowJob?.isActive == true) return
        flowJob = viewModelScope.launch {
            _ui.update { it.copy(power = ActionState.Loading, info = it.info.copy(state = transition(action, it.info.state))) }
            val source = api ?: run { _ui.update { it.copy(power = ActionState.Error("Configurá el relay")) }; return@launch }
            val direct = when (action) { PowerAction.LOCK -> source.lock(); PowerAction.WAKE -> source.wake(); else -> null }
            val coordinator = PcFlowCoordinator(source)
            val result = direct ?: if (action == PowerAction.RESTART) coordinator.restartAndWait(180_000) { pc -> _ui.update { it.copy(info = pc) } } else coordinator.waitForOffline(action, 90_000) { pc -> _ui.update { it.copy(info = pc) } }
            when (result) { is RelayResult.Success -> { _ui.update { it.copy(power = ActionState.Success("ORDEN ENVIADA"), snackbar = if (action == PowerAction.LOCK) "Windows bloqueado" else "Orden aceptada") }; if (action == PowerAction.LOCK) refreshNow() }; is RelayResult.Failure -> _ui.update { it.copy(power = ActionState.Error(result.userMessage), relayError = result.error) } }
            delay(1_200); _ui.update { if (it.power is ActionState.Success) it.copy(power = ActionState.Idle) else it }
        }
    }
    private fun transition(action: PowerAction, old: PcState) = when (action) { PowerAction.SHUTDOWN -> PcState.SHUTTING_DOWN; PowerAction.RESTART -> PcState.RESTARTING; PowerAction.SLEEP -> PcState.SLEEPING; PowerAction.HIBERNATE -> PcState.HIBERNATING; PowerAction.WAKE -> PcState.WAKING; PowerAction.LOCK -> old }
}

@Composable private fun MggxRoot(vm: PcViewModel = viewModel()) { val ui by vm.ui.collectAsStateWithLifecycle(); val context = LocalContext.current; val dark = isSystemInDarkTheme(); val colors = if (ui.settings.dynamicColors && Build.VERSION.SDK_INT >= 31) if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) else if (dark) darkColorScheme() else lightColorScheme(); MaterialTheme(colorScheme = colors) { if (!ui.loaded) Loading() else if (!ui.settings.onboardingDone) Onboarding(vm) else Shell(ui, vm) } }
@Composable private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun Onboarding(vm: PcViewModel) = Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { Spacer(Modifier.height(36.dp)); Text("MGGX PC Control", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("Tu PC, desde cualquier lugar.", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); Button({ vm.finishOnboarding(false) }, Modifier.fillMaxWidth()) { Text("CONFIGURAR AHORA") }; OutlinedButton({ vm.finishOnboarding(true) }, Modifier.fillMaxWidth()) { Text("USAR MODO DEMO") } } }

@Composable private fun Shell(ui: PcUiState, vm: PcViewModel) { var tab by remember { mutableIntStateOf(0) }; val snackbar = remember { SnackbarHostState() }; val context = LocalContext.current; val haptics = LocalHapticFeedback.current
    LaunchedEffect(ui.snackbar) { ui.snackbar?.let { snackbar.showSnackbar(it); vm.clearSnackbar() } }; LaunchedEffect(ui.open, ui.save) { if (ui.settings.haptics && (ui.open is ActionState.Success || ui.save is ActionState.Success)) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { NavigationBar { listOf(Icons.Default.Computer to "PC", Icons.Default.Info to "Diagnóstico", Icons.Default.Settings to "Ajustes").forEachIndexed { index, pair -> NavigationBarItem(index == tab, { tab = index }, { Icon(pair.first, null) }, label = { Text(pair.second) }) } } }) { padding -> AnimatedContent(tab, Modifier.padding(padding), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(140)) }, label = "tab") { when (it) { 0 -> Home(ui, vm) { openPackage(context, MOONLIGHT) }; 1 -> Diagnostics(ui, vm, context); else -> Settings(ui, vm, context) } } }
}

@Composable private fun Home(ui: PcUiState, vm: PcViewModel, moonlight: () -> Unit) { var confirm by remember { mutableStateOf<PowerAction?>(null) }; confirm?.let { action -> AlertDialog({ if (ui.power !is ActionState.Loading) confirm = null }, title = { Text("¿${powerLabel(action)} MGGX PC?") }, text = { Text("La conexión remota se cerrará.") }, confirmButton = { ActionButton({ vm.power(action); confirm = null }, ui.power, powerLabel(action).uppercase(), "PROCESANDO…") }, dismissButton = { TextButton({ confirm = null }) { Text("CANCELAR") } }) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("MGGX PC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); if (ui.settings.demoMode) Text("MODO DEMO", color = MaterialTheme.colorScheme.primary) }; IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualizar") } } }
        item { StatusCard(ui) }
        item { ActionButton({ vm.openPc(moonlight) }, ui.open, "ABRIR PC", "PREPARANDO PC…", Modifier.fillMaxWidth()) }
        if (ui.info.state == PcState.OFFLINE) item { OutlinedButton({ vm.wakeOnly() }, Modifier.fillMaxWidth(), enabled = ui.open !is ActionState.Loading) { Icon(Icons.Default.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text("PRENDER PC") } }
        if (ui.open is ActionState.Loading) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("PRENDIENDO PC… ${ui.elapsed / 1000} s"); OutlinedButton({ vm.cancelFlow() }, Modifier.fillMaxWidth()) { Text("CANCELAR") } } }
        if (ui.info.state == PcState.ONLINE && ui.info.sunshine.running == false) item { Card { Column(Modifier.padding(16.dp)) { Text("Windows está online, pero Sunshine no inició.", fontWeight = FontWeight.Bold); OutlinedButton({ vm.restartSunshine() }, enabled = ui.info.capabilities.sunshineRestart != false) { Text("REINTENTAR SUNSHINE") }; TextButton(moonlight) { Text("ABRIR MOONLIGHT IGUAL") } } } }
        if (ui.info.state == PcState.ONLINE) { item { Text("Power", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }; item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf(PowerAction.SHUTDOWN, PowerAction.RESTART, PowerAction.SLEEP, PowerAction.HIBERNATE).forEach { action -> OutlinedButton({ confirm = action }, Modifier.fillMaxWidth(), enabled = ui.info.capabilities.supports(action) && ui.power !is ActionState.Loading) { Text(powerLabel(action).uppercase()) } }; OutlinedButton({ vm.power(PowerAction.LOCK) }, Modifier.fillMaxWidth(), enabled = ui.info.capabilities.supports(PowerAction.LOCK) && ui.power !is ActionState.Loading) { Text("BLOQUEAR") } } } }
        ui.relayError?.let { item { ErrorCard(it.userMessage()) { vm.runDiagnostics() } } }
    }
}
@Composable private fun StatusCard(ui: PcUiState) = ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { AnimatedContent(ui.info.state, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) }, label = "state") { Status(it) }; StatusLine("Relay", if (ui.relayError == null) "Connected${ui.latency?.let { " · ${it} ms" }.orEmpty()}" else "No disponible"); StatusLine("Windows Agent", when { ui.info.state == PcState.OFFLINE -> "PC apagada"; ui.info.agent.reachable == true -> "Running${ui.info.agent.version?.let { " · v$it" }.orEmpty()}"; ui.info.agent.reachable == false -> "No responde"; else -> "Unknown" }); StatusLine("Sunshine", serviceText(ui.info.sunshine)); StatusLine("Tailscale PC", tailscaleText(ui.info.tailscale)); ui.info.agent.uptimeSeconds?.let { StatusLine("Windows uptime", formatDuration(it)) } } }
@Composable private fun StatusLine(name: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.Medium) }
private fun serviceText(s: ServiceStatus) = when (s.installed) { false -> "No instalado"; else -> when (s.running) { true -> "● Running"; false -> "○ Stopped"; null -> "— Unknown" } }
private fun tailscaleText(s: PcTailscaleStatus) = when (s.installed) { false -> "No instalado"; else -> when (s.running) { true -> "● Connected${s.ip?.let { " · $it" }.orEmpty()}"; false -> "○ Stopped"; null -> "— Unknown" } }
private fun formatDuration(seconds: Long): String = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h ${(seconds % 3600) / 60} min"

@Composable private fun Diagnostics(ui: PcUiState, vm: PcViewModel, context: Context) { var copied by remember { mutableStateOf(false) }; val report = ui.diagnostics; LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }; LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("Diagnóstico", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; items(listOf("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "Aplicación" to BuildConfig.VERSION_NAME, "Tailscale instalada" to installedLabel(context, TAILSCALE), "VPN activa" to (report?.vpnActive?.let { if (it) "Sí" else "No" } ?: "—"), "Relay URL" to (report?.endpoint?.displayUrl ?: ui.settings.relayUrl.ifBlank { "—" }), "PC" to ui.info.state.name, "Agent" to ui.info.agent.reachable.toString(), "Sunshine" to serviceText(ui.info.sunshine), "Tailscale PC" to tailscaleText(ui.info.tailscale), "Capabilities" to ui.info.capabilities.toString())) { (a, b) -> ListItem({ Text(a) }, supportingContent = { Text(b) }) }; report?.steps?.let { steps -> item { Text("Prueba de Relay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }; items(steps) { step -> ListItem({ Text(step.stage.label) }, supportingContent = { Text(step.detail.ifBlank { "Pendiente" }) }, leadingContent = { Icon(if (step.state == DiagnosticState.SUCCESS) Icons.Default.CheckCircle else if (step.state == DiagnosticState.FAILURE) Icons.Default.Error else Icons.Default.RadioButtonUnchecked, null) }) } }; item { ActionButton({ vm.runDiagnostics() }, ui.test, "PROBAR CONEXIÓN", "PROBANDO…", Modifier.fillMaxWidth()) }; item { OutlinedButton({ copyReport(context, ui); copied = true }, Modifier.fillMaxWidth()) { Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(if (copied) "COPIADO" else "COPIAR REPORTE SANITIZADO") } } } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Settings(ui: PcUiState, vm: PcViewModel, context: Context) { var url by remember(ui.settings.relayUrl) { mutableStateOf(ui.settings.relayUrl) }; var token by remember { mutableStateOf("") }; var tokenEdited by remember { mutableStateOf(false) }; var pcId by remember(ui.settings.pcId) { mutableStateOf(ui.settings.pcId) }; var timeout by remember(ui.settings.timeoutSeconds) { mutableStateOf(ui.settings.timeoutSeconds.toString()) }; val normalized = RelayUrlNormalizer.normalize(url); val dirty = url != ui.settings.relayUrl || pcId != ui.settings.pcId || timeout != ui.settings.timeoutSeconds.toString() || tokenEdited
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Relay URL") }, isError = normalized is RelayResult.Failure, supportingText = { Text(if (normalized is RelayResult.Success) "Se interpretará como: ${normalized.value.displayUrl}" else "URL inválida") }, singleLine = true) }; item { OutlinedTextField(token, { token = it; tokenEdited = true }, Modifier.fillMaxWidth(), label = { Text("Relay token") }, supportingText = { Text(if (tokenEdited) "Se actualiza al guardar" else "El token existente se conserva") }, singleLine = true) }; item { OutlinedTextField(pcId, { pcId = it }, Modifier.fillMaxWidth(), label = { Text("PC ID") }, singleLine = true) }; item { OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Timeout de red (segundos)") }, singleLine = true) }; if (dirty) item { Text("Cambios sin guardar", color = MaterialTheme.colorScheme.primary) }; item { ActionButton({ vm.saveRelay(url, token.takeIf { tokenEdited }, pcId, timeout.toIntOrNull() ?: 8) }, ui.save, "GUARDAR", "GUARDANDO…", Modifier.fillMaxWidth(), dirty && normalized is RelayResult.Success) }; item { OutlinedButton({ vm.saveRelay(url, token.takeIf { tokenEdited }, pcId, timeout.toIntOrNull() ?: 8, true) }, Modifier.fillMaxWidth(), enabled = dirty && normalized is RelayResult.Success && ui.save !is ActionState.Loading) { Text("GUARDAR Y PROBAR") } }; item { ListItem({ Text("Moonlight") }, supportingContent = { Text(installedLabel(context, MOONLIGHT)) }, trailingContent = { TextButton({ openPackage(context, MOONLIGHT) }) { Text("ABRIR") } }) }; item { OutlinedButton({ openPackage(context, TAILSCALE) }, Modifier.fillMaxWidth()) { Text("ABRIR TAILSCALE") } }; item { ListItem({ Text("Modo Demo") }, trailingContent = { Switch(ui.settings.demoMode, { vm.setDemo(it) }) }) }; if (ui.settings.demoMode) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DemoStatus.entries.forEach { value -> FilterChip(value == ui.settings.simulatedStatus, { vm.setDemoStatus(value) }, { Text(value.name) }) } } } } }

@Composable internal fun ActionButton(click: () -> Unit, state: ActionState, idle: String, loading: String, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(click, modifier, enabled = enabled && state !is ActionState.Loading) { when (state) { ActionState.Idle -> Text(idle); ActionState.Loading -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(loading) }; is ActionState.Success -> Text(state.label); is ActionState.Error -> Text("ERROR") } }
@Composable private fun Status(state: PcState) { val pair = when (state) { PcState.ONLINE -> Color(0xFF2E9D62) to "ONLINE"; PcState.OFFLINE -> MaterialTheme.colorScheme.outline to "OFFLINE"; PcState.WAKING -> Color(0xFFDB8B00) to "PRENDIENDO…"; PcState.SHUTTING_DOWN -> Color(0xFFDB8B00) to "APAGANDO…"; PcState.RESTARTING -> Color(0xFFDB8B00) to "REINICIANDO…"; PcState.SLEEPING -> Color(0xFFDB8B00) to "SUSPENDIENDO…"; PcState.HIBERNATING -> Color(0xFFDB8B00) to "HIBERNANDO…"; PcState.CONNECTING -> MaterialTheme.colorScheme.primary to "CONECTANDO"; PcState.ERROR -> MaterialTheme.colorScheme.error to "ERROR"; PcState.UNKNOWN -> MaterialTheme.colorScheme.outline to "UNKNOWN" }; Text("● ${pair.second}", color = pair.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
@Composable private fun ErrorCard(text: String, retry: () -> Unit) = Card { Column(Modifier.padding(16.dp)) { Text(text); TextButton(retry) { Text("REINTENTAR") } } }
private fun powerLabel(action: PowerAction) = when (action) { PowerAction.WAKE -> "Prender"; PowerAction.SHUTDOWN -> "Apagar"; PowerAction.RESTART -> "Reiniciar"; PowerAction.SLEEP -> "Suspender"; PowerAction.HIBERNATE -> "Hibernar"; PowerAction.LOCK -> "Bloquear" }
private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
private fun installedLabel(context: Context, pkg: String) = if (installed(context, pkg)) "Instalada" else "No instalada"
private fun openPackage(context: Context, pkg: String) { val launch = context.packageManager.getLaunchIntentForPackage(pkg); val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")); runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
private fun copyReport(context: Context, ui: PcUiState) { val info = ui.info; val text = buildString { appendLine("MGGX PC Control ${BuildConfig.VERSION_NAME}"); appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}"); appendLine("Relay: ${ui.diagnostics?.endpoint?.displayUrl ?: "<not-configured>"}"); appendLine("VPN active: ${ui.diagnostics?.vpnActive ?: "unknown"}"); ui.diagnostics?.steps?.forEach { appendLine("${it.stage.label}: ${it.state} ${it.detail}") }; appendLine("PC: ${info.state}"); appendLine("Agent: reachable=${info.agent.reachable} version=${info.agent.version}"); appendLine("Sunshine: installed=${info.sunshine.installed} running=${info.sunshine.running}"); appendLine("Tailscale PC: installed=${info.tailscale.installed} running=${info.tailscale.running} ip=${info.tailscale.ip}"); appendLine("Capabilities: ${info.capabilities}"); appendLine("Authorization: <redacted>") }; (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("MGGX diagnostics", text)) }
