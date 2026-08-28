package com.mggx.pccontrol

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.lazy.LazyRow
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

private const val MOONLIGHT_PACKAGE = "com.limelight"
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen(); super.onCreate(savedInstanceState); enableEdgeToEdge(); publishShortcuts()
        setContent { MggxRoot() }
    }
    private fun publishShortcuts() {
        if (Build.VERSION.SDK_INT >= 25) {
            val manager = getSystemService(ShortcutManager::class.java)
            manager.dynamicShortcuts = listOf("wake" to "Prender PC", "open" to "Abrir PC", "status" to "Estado").map { (id, label) ->
                ShortcutInfo.Builder(this, id).setShortLabel(label).setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(Intent(this, MainActivity::class.java).setAction("com.mggx.pccontrol.$id")).build()
            }
        }
    }
}

sealed interface ActionState {
    data object Idle : ActionState
    data object Loading : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

data class PcUiState(
    val configurationLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val info: PcInfo = PcInfo("main", "MGGX PC", PcState.UNKNOWN),
    val lastError: RelayError? = null,
    val technical: RelayTechnical? = null,
    val lastLatencyMs: Long? = null,
    val wakeElapsed: Int = 0,
    val saveState: ActionState = ActionState.Idle,
    val connectionState: ActionState = ActionState.Idle,
    val wakeState: ActionState = ActionState.Idle,
    val powerState: ActionState = ActionState.Idle,
    val diagnostics: ConnectionDiagnostics? = null,
    val snackbar: String? = null,
)

class PcViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _ui = MutableStateFlow(PcUiState())
    val ui: StateFlow<PcUiState> = _ui.asStateFlow()
    private var api: PcRelayApi? = null
    private var relayConfig: RelayConfig? = null
    private var wakeJob: Job? = null

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                if (settings.demoMode) {
                    api = DemoRelayApi({ settings.simulatedStatus }) { next -> viewModelScope.launch { store.setDemoStatus(next) } }
                    relayConfig = null
                } else when (val loaded = store.loadRelayConfig(settings)) {
                    is RelayResult.Success -> { relayConfig = loaded.value; api = HttpRelayApi(loaded.value) }
                    is RelayResult.Failure -> { relayConfig = null; api = null; _ui.update { it.copy(lastError = loaded.error, technical = loaded.technical) } }
                }
                _ui.update { old -> old.copy(configurationLoaded = true, settings = settings, info = old.info.copy(name = settings.pcName, pcId = settings.pcId)) }
            }
        }
    }

    fun finishOnboarding(demo: Boolean) = viewModelScope.launch { store.completeOnboarding(demo) }
    fun setDemo(enabled: Boolean) = viewModelScope.launch { store.setDemo(enabled) }
    fun setDemoStatus(status: DemoStatus) = viewModelScope.launch { store.setDemoStatus(status) }
    fun setAutoOpen(enabled: Boolean) = viewModelScope.launch { store.setAutoOpen(enabled) }
    fun clearSnackbar() = _ui.update { it.copy(snackbar = null) }

    fun saveRelay(url: String, token: String?, pcId: String, timeout: Int) {
        if (_ui.value.saveState is ActionState.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(saveState = ActionState.Loading) }
            when (val saved = store.saveRelayAndCreateConfig(url, token, pcId, timeout)) {
                is RelayResult.Success -> {
                    relayConfig = saved.value.config
                    api = HttpRelayApi(saved.value.config) // exact config; no DataStore Flow race
                    _ui.update { it.copy(saveState = ActionState.Success("Guardado"), snackbar = "Configuración guardada", lastError = null) }
                    delay(1_200); _ui.update { if (it.saveState is ActionState.Success) it.copy(saveState = ActionState.Idle) else it }
                }
                is RelayResult.Failure -> _ui.update { it.copy(saveState = ActionState.Error(saved.userMessage), lastError = saved.error, technical = saved.technical, snackbar = "No se pudo guardar la configuración") }
            }
        }
    }

    fun refresh() = viewModelScope.launch { refreshInternal() }
    private suspend fun refreshInternal(): PcInfo? {
        val current = api ?: run { _ui.update { it.copy(lastError = RelayError.InvalidUrl) }; return null }
        val started = SystemClock.elapsedRealtime()
        return when (val result = current.getStatus()) {
            is RelayResult.Success -> {
                _ui.update { it.copy(info = result.value, lastError = null, technical = null, lastLatencyMs = result.elapsedMs ?: (SystemClock.elapsedRealtime() - started)) }; result.value
            }
            is RelayResult.Failure -> { _ui.update { it.copy(info = it.info.copy(state = if (it.settings.demoMode) PcState.ERROR else PcState.UNKNOWN), lastError = result.error, technical = result.technical, lastLatencyMs = null) }; null }
        }
    }

    fun runDiagnostics() {
        if (_ui.value.connectionState is ActionState.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(connectionState = ActionState.Loading, diagnostics = ConnectionDiagnostics(tailscaleInstalled = installed(getApplication(), TAILSCALE_PACKAGE))) }
            val config = relayConfig ?: when (val loaded = store.loadRelayConfig(_ui.value.settings)) {
                is RelayResult.Success -> loaded.value
                is RelayResult.Failure -> { _ui.update { it.copy(connectionState = ActionState.Error(loaded.userMessage), lastError = loaded.error) }; return@launch }
            }
            val report = RelayDiagnosticsRunner(getApplication()).run(config) { progress -> _ui.update { it.copy(diagnostics = progress) } }
            if (report.error == null) _ui.update { it.copy(connectionState = ActionState.Success("Relay conectado"), info = report.pcInfo ?: it.info, lastError = null, technical = null, snackbar = "Relay conectado") }
            else _ui.update { it.copy(connectionState = ActionState.Error(report.error.userMessage()), lastError = report.error, technical = report.technical, snackbar = report.error.userMessage()) }
        }
    }

    fun wake(autoOpen: (() -> Unit)? = null) {
        if (wakeJob?.isActive == true || _ui.value.wakeState is ActionState.Loading) return
        wakeJob = viewModelScope.launch {
            _ui.update { it.copy(wakeState = ActionState.Loading, wakeElapsed = 0, lastError = null, info = it.info.copy(state = PcState.WAKING)) }
            when (val command = api?.wake() ?: RelayResult.Failure(RelayError.InvalidUrl)) {
                is RelayResult.Failure -> { _ui.update { it.copy(wakeState = ActionState.Error(command.userMessage), lastError = command.error, technical = command.technical, info = it.info.copy(state = PcState.ERROR)) }; return@launch }
                is RelayResult.Success -> _ui.update { it.copy(snackbar = "Orden Wake-on-LAN enviada") }
            }
            val started = SystemClock.elapsedRealtime(); val limit = _ui.value.settings.timeoutSeconds.coerceAtLeast(5) * 1_000L
            while (SystemClock.elapsedRealtime() - started < limit) {
                _ui.update { it.copy(wakeElapsed = ((SystemClock.elapsedRealtime() - started) / 1_000).toInt()) }
                if (refreshInternal()?.state == PcState.ONLINE) {
                    _ui.update { it.copy(wakeState = ActionState.Success("PC online"), snackbar = "MGGX PC está online") }
                    if (_ui.value.settings.autoOpen) autoOpen?.invoke(); delay(1_200); _ui.update { it.copy(wakeState = ActionState.Idle) }; return@launch
                }; delay(1_000)
            }
            _ui.update { it.copy(wakeState = ActionState.Error("La orden fue enviada, pero la PC todavía no confirmó conexión."), info = it.info.copy(state = PcState.WAKING)) }
        }
    }
    fun cancelWake() { wakeJob?.cancel(); _ui.update { it.copy(wakeState = ActionState.Idle, info = it.info.copy(state = PcState.UNKNOWN)) } }
    fun activateMonitor(id: String) = viewModelScope.launch { when (val result = api?.activateMonitor(id) ?: RelayResult.Failure(RelayError.InvalidUrl)) { is RelayResult.Success -> refreshInternal(); is RelayResult.Failure -> _ui.update { it.copy(lastError = result.error, technical = result.technical) } } }
    fun remoteAction(action: RemoteAction) = viewModelScope.launch { when (val result = api?.action(action) ?: RelayResult.Failure(RelayError.InvalidUrl)) { is RelayResult.Success -> _ui.update { it.copy(snackbar = "Acción enviada") }; is RelayResult.Failure -> _ui.update { it.copy(lastError = result.error, technical = result.technical, snackbar = result.userMessage) } } }
    fun power(action: PowerAction) {
        if (_ui.value.powerState is ActionState.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(powerState = ActionState.Loading) }
            val result = when (action) { PowerAction.SHUTDOWN -> api?.shutdown(); PowerAction.RESTART -> api?.restart(); PowerAction.SLEEP -> api?.sleep(); PowerAction.HIBERNATE -> api?.hibernate(); PowerAction.WAKE -> api?.wake() } ?: RelayResult.Failure(RelayError.InvalidUrl)
            when (result) { is RelayResult.Success -> { _ui.update { it.copy(powerState = ActionState.Success("Orden enviada"), snackbar = "Orden enviada") }; delay(250); refreshInternal(); delay(1_000); _ui.update { it.copy(powerState = ActionState.Idle) } }; is RelayResult.Failure -> _ui.update { it.copy(powerState = ActionState.Error(result.userMessage), lastError = result.error, technical = result.technical) } }
        }
    }
}

@Composable private fun MggxRoot(vm: PcViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle(); val dark = isSystemInDarkTheme(); val context = LocalContext.current
    val colors = when { ui.settings.dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context); dark -> darkColorScheme(primary = Color(0xFFB9C3FF)); else -> lightColorScheme(primary = Color(0xFF4255D4)) }
    MaterialTheme(colorScheme = colors) { if (!ui.configurationLoaded) LoadingConfiguration() else if (!ui.settings.onboardingDone) Onboarding(vm) else MainShell(ui, vm) }
}

@Composable private fun LoadingConfiguration() = Surface(Modifier.fillMaxSize()) { Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Cargando configuración…") } } }

@Composable private fun Onboarding(vm: PcViewModel) { var page by remember { mutableIntStateOf(0) }; Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) { Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { Icon(Icons.Default.DesktopWindows, null, Modifier.size(72.dp), MaterialTheme.colorScheme.primary); Text("MGGX PC Control", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text(if (page == 0) "Tu PC, desde cualquier lugar." else "Necesitás Tailscale, Moonlight, un relay en casa y Sunshine en tu PC.", style = MaterialTheme.typography.titleLarge) }; if (page == 0) Button({ page = 1 }, Modifier.fillMaxWidth()) { Text("CONTINUAR") } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Button({ vm.finishOnboarding(false) }, Modifier.fillMaxWidth()) { Text("CONFIGURAR AHORA") }; OutlinedButton({ vm.finishOnboarding(true) }, Modifier.fillMaxWidth()) { Text("USAR MODO DEMO") } } } }

@Composable private fun MainShell(ui: PcUiState, vm: PcViewModel) {
    var tab by remember { mutableIntStateOf(0) }; val snackbar = remember { SnackbarHostState() }; val context = LocalContext.current; val haptics = LocalHapticFeedback.current
    LaunchedEffect(ui.snackbar) { ui.snackbar?.let { snackbar.showSnackbar(it); vm.clearSnackbar() } }
    LaunchedEffect(ui.saveState, ui.wakeState) { if (ui.settings.haptics && (ui.saveState is ActionState.Success || ui.wakeState is ActionState.Success)) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { NavigationBar { listOf(Icons.Default.Computer to "PC", Icons.Default.Info to "Diagnóstico", Icons.Default.Settings to "Ajustes").forEachIndexed { index, (icon, label) -> NavigationBarItem(selected = index == tab, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(label) }) } } }) { padding ->
        AnimatedContent(tab, Modifier.padding(padding), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) }, label = "tab") { selected -> when (selected) { 0 -> Home(ui, vm) { openPackage(context, MOONLIGHT_PACKAGE) }; 1 -> Diagnostics(ui, vm, context); else -> Settings(ui, vm, context) } }
    }
}

@Composable private fun Home(ui: PcUiState, vm: PcViewModel, openMoonlight: () -> Unit) {
    var confirmation by remember { mutableStateOf<PowerAction?>(null) }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { if (ui.powerState !is ActionState.Loading) confirmation = null }, title = { Text("¿${actionLabel(action)} MGGX PC?") }, text = { Text(if (ui.powerState is ActionState.Loading) "Procesando…" else "Esta acción puede cerrar la sesión remota.") }, confirmButton = { ActionButton({ vm.power(action) }, ui.powerState, actionLabel(action).uppercase(), "PROCESANDO…") }, dismissButton = { TextButton({ confirmation = null }, enabled = ui.powerState !is ActionState.Loading) { Text("CANCELAR") } }) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("MGGX PC Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); if (ui.settings.demoMode) Text("MODO DEMO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }; IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualizar estado") } } }
        item { PcPanel(ui, vm, openMoonlight) }; item { SectionTitle("Monitores") }
        item { if (ui.info.monitors.isEmpty()) Text("Los monitores aparecerán cuando el servicio PC responda.", color = MaterialTheme.colorScheme.onSurfaceVariant) else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.info.monitors, key = { it.id }) { monitor -> FilterChip(selected = monitor.active, onClick = { vm.activateMonitor(monitor.id) }, label = { Text(monitor.name) }, leadingIcon = if (monitor.active) {{ Icon(Icons.Default.Check, null) }} else null) } } }
        item { SectionTitle("Acciones rápidas") }; item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(RemoteAction.CAMERA to Icons.Default.CameraAlt, RemoteAction.TERMINAL to Icons.Default.Terminal, RemoteAction.FILES to Icons.Default.Folder, RemoteAction.TASK_MANAGER to Icons.Default.Memory)) { (action, icon) -> AssistChip(onClick = { vm.remoteAction(action) }, enabled = ui.info.state == PcState.ONLINE, label = { Text(actionName(action)) }, leadingIcon = { Icon(icon, null) }) } } }
        item { SectionTitle("Energía") }; item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(PowerAction.RESTART, PowerAction.SLEEP, PowerAction.HIBERNATE, PowerAction.SHUTDOWN)) { action -> OutlinedButton({ confirmation = action }, enabled = ui.powerState !is ActionState.Loading) { Text(actionLabel(action)) } } } }
        ui.lastError?.let { error -> item { ErrorCard(error.userMessage()) { vm.runDiagnostics() } } }
    }
}

@Composable private fun PcPanel(ui: PcUiState, vm: PcViewModel, openMoonlight: () -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(ui.info.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); AnimatedContent(ui.info.state, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) }, label = "pc-state") { Status(it) } }; Icon(Icons.Default.DesktopWindows, null, Modifier.size(52.dp), MaterialTheme.colorScheme.primary) }; if (ui.info.state == PcState.WAKING) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Esperando que Windows responda · ${ui.wakeElapsed} s"); OutlinedButton({ vm.cancelWake() }, Modifier.fillMaxWidth()) { Text("CANCELAR") } } else { ActionButton({ vm.wake(openMoonlight) }, ui.wakeState, "PRENDER PC", "PRENDIENDO…", Modifier.fillMaxWidth(), enabled = ui.info.state != PcState.ONLINE); OutlinedButton(openMoonlight, Modifier.fillMaxWidth(), enabled = ui.info.state == PcState.ONLINE) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("ABRIR EN MOONLIGHT") } } } }

@Composable private fun Diagnostics(ui: PcUiState, vm: PcViewModel, context: Context) {
    val report = ui.diagnostics; val baseRows = listOf("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "Aplicación" to BuildConfig.VERSION_NAME, "Tailscale" to installedLabel(context, TAILSCALE_PACKAGE), "Moonlight" to installedLabel(context, MOONLIGHT_PACKAGE), "Relay configurado" to if (ui.settings.relayUrl.isBlank()) "No" else "Sí", "URL" to (report?.endpoint?.displayUrl ?: ui.settings.relayUrl.ifBlank { "—" }), "VPN activa" to (report?.vpnActive?.let { if (it) "Sí" else "No" } ?: "—"), "PC" to (report?.pcInfo?.state?.name ?: ui.info.state.name), "Último error técnico" to (ui.technical?.let { "${it.stage} · ${it.exceptionType ?: it.httpCode ?: "—"}" } ?: "—"))
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("Diagnóstico", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; items(baseRows) { (name, value) -> ListItem(headlineContent = { Text(name) }, supportingContent = { Text(value) }) }; report?.steps?.let { steps -> item { SectionTitle("Prueba de relay") }; items(steps) { step -> ListItem(headlineContent = { Text(step.stage.label) }, supportingContent = { Text(step.detail.ifBlank { if (step.state == DiagnosticState.PENDING) "Pendiente" else "" }) }, leadingContent = { when (step.state) { DiagnosticState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); DiagnosticState.SUCCESS -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E9D62)); DiagnosticState.FAILURE -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error); else -> Icon(Icons.Default.RadioButtonUnchecked, null) } }) } }; item { ActionButton({ vm.runDiagnostics() }, ui.connectionState, "PROBAR CONEXIÓN", "PROBANDO…", Modifier.fillMaxWidth()) }; item { OutlinedButton({ copyReport(context, ui) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("COPIAR REPORTE SANITIZADO") } } }
}

@Composable private fun Settings(ui: PcUiState, vm: PcViewModel, context: Context) {
    var url by remember(ui.settings.relayUrl) { mutableStateOf(ui.settings.relayUrl) }; var token by remember { mutableStateOf("") }; var tokenEdited by remember { mutableStateOf(false) }; var pcId by remember(ui.settings.pcId) { mutableStateOf(ui.settings.pcId) }; var timeout by remember(ui.settings.timeoutSeconds) { mutableStateOf(ui.settings.timeoutSeconds.toString()) }
    val normal = RelayUrlNormalizer.normalize(url); val urlHint = when (normal) { is RelayResult.Success -> "Se interpretará como: ${normal.value.displayUrl}"; is RelayResult.Failure -> if (url.isBlank()) "Ingresá la URL del relay" else normal.userMessage }
    val dirty = url != ui.settings.relayUrl || pcId != ui.settings.pcId || timeout != ui.settings.timeoutSeconds.toString() || tokenEdited
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; item { SectionTitle("Relay") }
        item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL del relay") }, singleLine = true, isError = normal is RelayResult.Failure && url.isNotBlank(), supportingText = { Text(urlHint) }) }
        item { OutlinedTextField(token, { token = it; tokenEdited = true }, Modifier.fillMaxWidth(), label = { Text("API token") }, singleLine = true, supportingText = { Text(if (tokenEdited) "Token actualizado al guardar" else "Token guardado se mantiene si no lo modificás") }) }
        item { OutlinedTextField(pcId, { pcId = it }, Modifier.fillMaxWidth(), label = { Text("PC ID") }, singleLine = true) }; item { OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Timeout (segundos)") }, singleLine = true, isError = timeout.toIntOrNull()?.let { it !in 2..60 } ?: true, supportingText = { Text("Entre 2 y 60 segundos") }) }
        item { if (dirty) Text("Cambios sin guardar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }; item { ActionButton({ vm.saveRelay(url, token.takeIf { tokenEdited }, pcId, timeout.toIntOrNull() ?: 8) }, ui.saveState, "GUARDAR", "GUARDANDO…", Modifier.fillMaxWidth(), enabled = dirty && normal is RelayResult.Success && timeout.toIntOrNull() in 2..60) }
        item { SectionTitle("Remote Desktop") }; item { ListItem(headlineContent = { Text("Moonlight") }, supportingContent = { Text(installedLabel(context, MOONLIGHT_PACKAGE)) }, trailingContent = { TextButton({ openPackage(context, MOONLIGHT_PACKAGE) }) { Text("ABRIR") } }) }; item { ListItem(headlineContent = { Text("Prender y conectar automáticamente") }, trailingContent = { Switch(ui.settings.autoOpen, { vm.setAutoOpen(it) }) }) }
        item { SectionTitle("Tailscale") }; item { OutlinedButton({ openPackage(context, TAILSCALE_PACKAGE) }, Modifier.fillMaxWidth()) { Text("ABRIR TAILSCALE") } }
        item { SectionTitle("Desarrollador") }; item { ListItem(headlineContent = { Text("Modo Demo") }, supportingContent = { Text("Simula el relay y la PC localmente") }, trailingContent = { Switch(ui.settings.demoMode, { vm.setDemo(it) }) }) }; if (ui.settings.demoMode) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(DemoStatus.entries) { status -> FilterChip(selected = status == ui.settings.simulatedStatus, onClick = { vm.setDemoStatus(status) }, label = { Text(status.name) }) } } }
    }
}

@Composable private fun ActionButton(onClick: () -> Unit, state: ActionState, idle: String, loading: String, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(onClick, modifier, enabled = enabled && state !is ActionState.Loading) { when (state) { ActionState.Idle -> Text(idle); ActionState.Loading -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text(loading) }; is ActionState.Success -> { Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text(state.message.uppercase()) }; is ActionState.Error -> { Icon(Icons.Default.Error, null); Spacer(Modifier.width(8.dp)); Text("ERROR") } } }
@Composable private fun Status(state: PcState) { val (color, label) = when (state) { PcState.ONLINE -> Color(0xFF2E9D62) to "ONLINE"; PcState.WAKING -> Color(0xFFDB8B00) to "PRENDIENDO…"; PcState.ERROR -> MaterialTheme.colorScheme.error to "ERROR"; PcState.CONNECTING -> MaterialTheme.colorScheme.primary to "CONECTANDO"; PcState.OFFLINE -> MaterialTheme.colorScheme.outline to "OFFLINE"; PcState.UNKNOWN -> MaterialTheme.colorScheme.outline to "UNKNOWN" }; Row(verticalAlignment = Alignment.CenterVertically) { Text("●", color = color); Spacer(Modifier.width(6.dp)); Text(label, color = color, fontWeight = FontWeight.Bold) } }
@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
@Composable private fun ErrorCard(message: String, retry: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); TextButton(retry) { Text("REINTENTAR") } } }
private fun actionLabel(action: PowerAction) = when (action) { PowerAction.WAKE -> "Prender"; PowerAction.SHUTDOWN -> "Apagar"; PowerAction.RESTART -> "Reiniciar"; PowerAction.SLEEP -> "Suspender"; PowerAction.HIBERNATE -> "Hibernar" }
private fun actionName(action: RemoteAction) = when (action) { RemoteAction.CAMERA -> "Cámara"; RemoteAction.TERMINAL -> "Terminal"; RemoteAction.FILES -> "Archivos"; RemoteAction.ADMIN -> "Administrador"; RemoteAction.TASK_MANAGER -> "Task Manager"; RemoteAction.LOCK -> "Bloquear" }
private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
private fun installedLabel(context: Context, pkg: String) = if (installed(context, pkg)) "Instalado" else "No instalado"
private fun openPackage(context: Context, pkg: String) { val launch = context.packageManager.getLaunchIntentForPackage(pkg); val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")); runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
private fun copyReport(context: Context, ui: PcUiState) { val report = buildString { appendLine("MGGX PC Control ${BuildConfig.VERSION_NAME}"); appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}"); val endpoint = ui.diagnostics?.endpoint; appendLine("Relay host: ${endpoint?.host ?: "<not-configured>"}"); appendLine("Relay port: ${endpoint?.port ?: "—"}"); appendLine("Scheme: ${endpoint?.scheme ?: "—"}"); appendLine("VPN active: ${ui.diagnostics?.vpnActive ?: "unknown"}"); ui.diagnostics?.steps?.forEach { appendLine("${it.stage.label}: ${it.state} ${it.detail}") }; appendLine("Error type: ${ui.lastError?.javaClass?.simpleName ?: "—"}"); appendLine("HTTP code: ${ui.technical?.httpCode ?: "—"}"); appendLine("Latency: ${ui.lastLatencyMs ?: "—"}"); appendLine("Authorization: <redacted>") }; (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("MGGX diagnostics", report)) }
