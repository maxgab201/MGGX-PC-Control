package com.mggx.pccontrol.next

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mggx.pccontrol.next.data.NextSettings
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.home.HomeDeviceRuntime
import com.mggx.pccontrol.next.home.HomeDeviceService
import com.mggx.pccontrol.next.home.HomeClaimResult
import com.mggx.pccontrol.next.home.HomePairingClient
import com.mggx.pccontrol.next.pairing.PairingParseResult
import com.mggx.pccontrol.next.pairing.PairingProtocol
import com.mggx.pccontrol.next.v2.DeviceRole
import com.mggx.pccontrol.next.v2.HomeDeviceConfig
import com.mggx.pccontrol.next.v2.OnboardingStep
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
private const val MOONLIGHT_PACKAGE = "com.limelight"

class NextMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { MaterialTheme { NextRoot() } }
    }
}

class NextViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NextSettingsStore(application)
    private val pairingClient = HomePairingClient(store)
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NextSettings())
    val homeRuntime = HomeDeviceRuntime.state
    fun choose(role: DeviceRole) = viewModelScope.launch { store.selectRole(role) }
    fun next(step: OnboardingStep) = viewModelScope.launch { store.setStep(step) }
    fun resume(step: OnboardingStep) = viewModelScope.launch { store.resumeSetup(step) }
    fun complete() = viewModelScope.launch { store.complete() }
    fun startHome() = viewModelScope.launch { val current = store.snapshot(); store.saveHome(current.home.copy(enabled = true)); HomeDeviceService.start(getApplication()) }
    fun saveHomeAndStart(agentUrl: String, agentToken: String, mac: String, broadcast: String) = viewModelScope.launch {
        val current = store.snapshot(); if (!store.saveAgentToken(agentToken.trim())) return@launch
        store.saveHome(current.home.copy(enabled = true, agentUrl = agentUrl.trim(), wakeOnLan = current.home.wakeOnLan.copy(macAddress = mac.trim(), broadcastAddress = broadcast.trim())))
        HomeDeviceService.start(getApplication())
        store.complete()
    }
    fun saveLegacy(url: String, pcId: String, token: String) = viewModelScope.launch { store.saveLegacy(url, pcId, token) }
    fun claimHome(raw: String, onResult: (String) -> Unit) = viewModelScope.launch {
        when (val parsed = PairingProtocol.parse(raw)) {
            is PairingParseResult.Invalid -> onResult(parsed.reason)
            is PairingParseResult.Valid -> when (val result = pairingClient.claim(parsed.offer)) {
                is HomeClaimResult.Success -> {
                    store.setStep(OnboardingStep.CONTROL_SUNSHINE)
                    onResult("Celular en casa vinculado a ${result.pcName} ✓")
                }
                is HomeClaimResult.Failure -> onResult(result.message)
            }
        }
    }
}

@Composable private fun NextRoot(viewModel: NextViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    when (settings.role) {
        DeviceRole.UNSELECTED -> RoleOnboarding(viewModel)
        DeviceRole.CONTROL_PHONE -> if (!settings.setupComplete) ControlWizard(settings, viewModel) else ControlDashboard(settings, viewModel)
        DeviceRole.HOME_PHONE -> if (!settings.setupComplete) HomeWizard(settings, viewModel) else HomeDashboard(settings, viewModel)
    }
}

@Composable private fun RoleOnboarding(vm: NextViewModel) = Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(32.dp)); Text("MGGX PC Control", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Controlá y usá tu PC desde cualquier lugar.", style = MaterialTheme.typography.titleLarge)
        Text("Vamos a configurarlo juntos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("¿Cómo vas a usar este celular?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        RoleButton("ESTE ES MI CELULAR DE USO COTIDIANO", "Lo voy a usar para prender, apagar y acceder a mi PC.") { vm.choose(DeviceRole.CONTROL_PHONE) }
        RoleButton("ESTE CELULAR QUEDARÁ EN CASA", "Va a permanecer junto a la PC y permitirá controlarla a distancia.") { vm.choose(DeviceRole.HOME_PHONE) }
    }
}

@Composable private fun RoleButton(title: String, detail: String, action: () -> Unit) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(action, Modifier.fillMaxWidth()) { Text("ELEGIR ESTA FUNCIÓN") } } }

@Composable private fun ControlWizard(settings: NextSettings, vm: NextViewModel) {
    val context = LocalContext.current
    WizardFrame("Configuración de tu celular", settings.step, vm) {
        when (settings.step) {
            OnboardingStep.CONTROL_PREPARE_PHONE -> {
                Text("Primero vamos a preparar este celular.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                AppRequirement("Tailscale", "Conecta tus dispositivos de forma privada desde cualquier lugar.", TAILSCALE_PACKAGE, context)
                AppRequirement("Moonlight", "Lo vas a usar para ver y controlar la pantalla de tu PC.", MOONLIGHT_PACKAGE, context)
                Button({ vm.next(OnboardingStep.CONTROL_PREPARE_HOME_PHONE) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") }
            }
            OnboardingStep.CONTROL_PREPARE_HOME_PHONE -> {
                Text("Ahora necesitás otro celular.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Puede ser un celular viejo. Solo necesita Android 8 o superior, Wi‑Fi y poder quedar conectado al cargador.")
                Text("En ese celular instalá MGGX PC Control 2 y Tailscale. Después elegí “Este celular quedará en casa”.")
                Button({ vm.next(OnboardingStep.CONTROL_PAIR_HOME) }, Modifier.fillMaxWidth()) { Text("YA LO PREPARÉ") }
            }
            OnboardingStep.CONTROL_PAIR_HOME -> HomePairingStep(vm)
            OnboardingStep.CONTROL_SUNSHINE -> {
                Text("Ahora vamos a preparar Sunshine.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Sunshine permite que Moonlight muestre y controle tu PC. Instalalo en la PC, abrilo y creá tu usuario y contraseña.")
                Button({ vm.next(OnboardingStep.CONTROL_MOONLIGHT_LAN) }, Modifier.fillMaxWidth()) { Text("YA CONFIGURÉ SUNSHINE") }
            }
            OnboardingStep.CONTROL_MOONLIGHT_LAN -> MoonlightStep("Primero conectaremos Moonlight dentro de tu casa.", "La IP local se obtiene automáticamente después de vincular la PC. Abrí Moonlight, tocá + y agregala. No cierres la pantalla del PIN.") { vm.next(OnboardingStep.CONTROL_MOONLIGHT_TAILSCALE) }
            OnboardingStep.CONTROL_MOONLIGHT_TAILSCALE -> MoonlightStep("Ahora configuraremos el acceso desde fuera de casa.", "No elimines la conexión anterior. Agregá también la dirección segura de Tailscale que informe la PC.") { vm.next(OnboardingStep.VERIFY) }
            OnboardingStep.VERIFY -> {
                Text("Comprobando conexión", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Checklist(listOf("Celular en casa", "Conexión segura", "PC", "MGGX PC Agent", "Sunshine"))
                Button({ vm.complete() }, Modifier.fillMaxWidth()) { Text("FINALIZAR") }
            }
            else -> vm.next(OnboardingStep.CONTROL_PREPARE_PHONE)
        }
    }
}

@Composable private fun HomeWizard(settings: NextSettings, vm: NextViewModel) {
    val context = LocalContext.current
    WizardFrame("Configuración del celular de casa", settings.step, vm) {
        when (settings.step) {
            OnboardingStep.HOME_PREPARE_TAILSCALE -> {
                Text("Instalá y configurá Tailscale con la misma cuenta que usaste en tu celular principal.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                AppRequirement("Tailscale", "Es la conexión segura entre este celular y tu celular principal.", TAILSCALE_PACKAGE, context)
                Button({ vm.next(OnboardingStep.HOME_ALWAYS_ON_VPN) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") }
            }
            OnboardingStep.HOME_ALWAYS_ON_VPN -> {
                Text("Mantené activa la conexión segura", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Abrí Ajustes, buscá VPN, entrá a Tailscale y activá “VPN siempre activa”, “Permitir siempre” o “Always-on VPN”. No actives “Bloquear conexiones sin VPN”.")
                OutlinedButton({ open(context, Intent(Settings.ACTION_VPN_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("ABRIR AJUSTES DE VPN") }
                Button({ vm.next(OnboardingStep.HOME_BATTERY) }, Modifier.fillMaxWidth()) { Text("YA LO HICE") }
            }
            OnboardingStep.HOME_BATTERY -> {
                Text("Evitar que Android pause las aplicaciones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("En Información de la aplicación > Batería elegí Sin restricciones para MGGX PC Control 2 y revisá Tailscale manualmente.")
                OutlinedButton({ requestBatteryOptimization(context) }, Modifier.fillMaxWidth()) { Text("PERMITIR PARA MGGX") }
                Button({ vm.next(OnboardingStep.HOME_PAIR_PC) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") }
            }
            OnboardingStep.HOME_PAIR_PC -> AgentPairingStep(vm)
            else -> vm.next(OnboardingStep.HOME_PREPARE_TAILSCALE)
        }
    }
}

@Composable private fun WizardFrame(title: String, step: OnboardingStep, vm: NextViewModel, content: @Composable () -> Unit) = Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { item { AssistChip({}, label = { Text("Paso ${step.ordinal + 1}") }) }; item { content() } } }

@Composable private fun AppRequirement(name: String, explanation: String, packageName: String, context: Context) { val installed = remember(packageName) { installed(context, packageName) }; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(explanation); Text(if (installed) "INSTALADO ✓" else "Todavía no está instalado", color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ openStore(context, packageName) }) { Text("INSTALAR") }; Button({ openPackage(context, packageName) }) { Text("ABRIR") } } } }

@Composable private fun HomePairingStep(vm: NextViewModel) { var payload by remember { mutableStateOf("") }; var result by remember { mutableStateOf<String?>(null) }; Text("Vinculá el celular que queda en casa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Escaneá el código que muestra el otro celular. Como alternativa temporal para desarrollo podés pegar el código aquí."); TextField(payload, { payload = it }, Modifier.fillMaxWidth(), label = { Text("Código de vinculación") }); result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }; Button({ vm.claimHome(payload) { result = it } }, Modifier.fillMaxWidth(), enabled = payload.isNotBlank()) { Icon(Icons.Default.QrCodeScanner, null); Text(" VINCULAR") } }

@Composable private fun AgentPairingStep(vm: NextViewModel) { var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }; var mac by remember { mutableStateOf("") }; var broadcast by remember { mutableStateOf("") }; Text("Vinculá tu PC", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("El MGGX PC Agent mostrará un código temporal para escanear. Esa vinculación segura estará disponible cuando el Agent actualizado esté instalado."); Text("Para probar hoy con el sistema actual podés abrir las opciones avanzadas.", color = MaterialTheme.colorScheme.onSurfaceVariant); var advanced by remember { mutableStateOf(false) }; OutlinedButton({ advanced = !advanced }, Modifier.fillMaxWidth()) { Text("OPCIONES AVANZADAS DE PRUEBA") }; if (advanced) { TextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Dirección de prueba del Agent") }); TextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Credencial del Agent") }); TextField(mac, { mac = it }, Modifier.fillMaxWidth(), label = { Text("MAC para encendido remoto") }); TextField(broadcast, { broadcast = it }, Modifier.fillMaxWidth(), label = { Text("Dirección de red de casa") }); Button({ vm.saveHomeAndStart(url, token, mac, broadcast) }, Modifier.fillMaxWidth(), enabled = url.isNotBlank() && token.isNotBlank()) { Text("GUARDAR Y ACTIVAR") } } }

@Composable private fun MoonlightStep(title: String, body: String, next: () -> Unit) { val context = LocalContext.current; Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(body); OutlinedButton({ openPackage(context, MOONLIGHT_PACKAGE) }, Modifier.fillMaxWidth()) { Text("ABRIR MOONLIGHT") }; Button(next, Modifier.fillMaxWidth()) { Text("CONTINUAR") } }
@Composable private fun Checklist(entries: List<String>) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { entries.forEach { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Text("  $it") } } }

@Composable private fun ControlDashboard(settings: NextSettings, vm: NextViewModel) = DashboardShell("MGGX PC", Icons.Default.Computer) { Text("Celular principal", color = MaterialTheme.colorScheme.primary); StatusRow("Celular en casa", if (settings.pairedHomeHost.isBlank()) "Todavía no vinculado" else "Conectado"); StatusRow("PC", "Usá Diagnóstico para comprobar el estado en vivo"); Button({ vm.resume(OnboardingStep.CONTROL_PREPARE_PHONE) }, Modifier.fillMaxWidth()) { Text("CONTINUAR CONFIGURACIÓN") }; LegacyCard(settings, vm) }
@Composable private fun HomeDashboard(settings: NextSettings, vm: NextViewModel) { val runtime by vm.homeRuntime.collectAsState(); DashboardShell("Este celular mantiene tu PC disponible", Icons.Default.Home) { Text("Dejalo conectado al cargador y al Wi‑Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant); StatusRow("Servicio", if (runtime.serverRunning) "Activo ✓" else "Detenido"); StatusRow("Conexión segura", if (runtime.vpnActive) "Conectada ✓" else "Revisar Tailscale"); StatusRow("PC", runtime.state.name.replace('_', ' ')); Button({ vm.startHome() }, Modifier.fillMaxWidth()) { Text("REINICIAR CONEXIÓN") }; Text("El código para vincular otro celular se muestra una vez que la conexión segura tenga una dirección disponible.") } }
@Composable private fun DashboardShell(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) = Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { Icon(icon, null, Modifier.padding(12.dp)) }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } }
@Composable private fun StatusRow(name: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(value, fontWeight = FontWeight.Medium) }
@Composable private fun LegacyCard(settings: NextSettings, vm: NextViewModel) { var show by remember { mutableStateOf(false) }; var url by remember { mutableStateOf(settings.legacyUrl) }; var token by remember { mutableStateOf("") }; var pcId by remember { mutableStateOf(settings.legacyPcId) }; OutlinedButton({ show = !show }, Modifier.fillMaxWidth()) { Text("AVANZADO / COMPATIBILIDAD CON SISTEMA ACTUAL") }; if (show) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Estas opciones son para diagnóstico o configuración manual.", fontWeight = FontWeight.Bold); TextField(url, { url = it }, label = { Text("Dirección del Relay") }); TextField(token, { token = it }, label = { Text("Token (no se muestra después)") }); TextField(pcId, { pcId = it }, label = { Text("ID de PC") }); Button({ vm.saveLegacy(url, pcId, token) }) { Text("GUARDAR CONFIGURACIÓN DE PRUEBA") } } } }

@Suppress("DEPRECATION") private fun installed(context: Context, packageName: String) = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
private fun openPackage(context: Context, packageName: String) = runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
private fun openStore(context: Context, packageName: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
private fun open(context: Context, intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
private fun requestBatteryOptimization(context: Context) = runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
