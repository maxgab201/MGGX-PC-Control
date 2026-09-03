package com.mggx.pccontrol.next

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mggx.pccontrol.next.data.NextSettings
import com.mggx.pccontrol.next.domain.PcState
import com.mggx.pccontrol.next.domain.PowerAction
import com.mggx.pccontrol.next.pairing.*
import com.mggx.pccontrol.next.home.HomeOfferPhase
import com.mggx.pccontrol.next.v2.*
import kotlinx.coroutines.delay

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
private const val MOONLIGHT_PACKAGE = "com.limelight"

class NextMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { MaterialTheme { NextRoot() } }
    }
}

@Composable private fun NextRoot(vm: NextViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle(); val message by vm.message.collectAsStateWithLifecycle()
    when (settings.role) {
        DeviceRole.UNSELECTED -> RoleOnboarding(vm)
        DeviceRole.CONTROL_PHONE -> if (!settings.setupComplete) ControlWizard(settings, vm) else ControlDashboard(settings, vm)
        DeviceRole.HOME_PHONE -> if (!settings.setupComplete) HomeWizard(settings, vm) else HomeDashboard(settings, vm)
    }
    message?.let { AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(vm::clearMessage) { Text("ACEPTAR") } }, text = { Text(it) }) }
}

@Composable private fun RoleOnboarding(vm: NextViewModel) = Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(32.dp)); Text("MGGX PC Control", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Controlá y usá tu PC desde cualquier lugar.", style = MaterialTheme.typography.titleLarge); Text("Vamos a configurarlo juntos.")
        Spacer(Modifier.weight(1f)); Text("¿Cómo vas a usar este celular?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        RoleButton("ESTE ES MI CELULAR DE USO COTIDIANO", "Lo voy a usar para prender, apagar y acceder a mi PC.") { vm.choose(DeviceRole.CONTROL_PHONE) }
        RoleButton("ESTE CELULAR QUEDARÁ EN CASA", "Va a permanecer junto a la PC y permitirá controlarla a distancia.") { vm.choose(DeviceRole.HOME_PHONE) }
    }
}

@Composable private fun RoleButton(title: String, detail: String, action: () -> Unit) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail); Button(action, Modifier.fillMaxWidth()) { Text("ELEGIR ESTA FUNCIÓN") } } }

@Composable private fun ControlWizard(settings: NextSettings, vm: NextViewModel) {
    val context = LocalContext.current
    WizardFrame("Configuración de tu celular", settings.role, settings.step) {
        when (settings.step) {
            OnboardingStep.CONTROL_PREPARE_PHONE -> { Heading("Preparar este celular"); AppRequirement("Tailscale", "Conecta tus dispositivos de forma privada.", TAILSCALE_PACKAGE, context); AppRequirement("Moonlight", "Muestra y controla la pantalla de tu PC.", MOONLIGHT_PACKAGE, context); Button({ vm.next(OnboardingStep.CONTROL_PREPARE_HOME_PHONE) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") } }
            OnboardingStep.CONTROL_PREPARE_HOME_PHONE -> { Heading("Preparar el celular que quedará en casa"); Text("Instalá MGGX PC Control 2 y Tailscale en otro celular con Android 8 o superior. Elegí “Este celular quedará en casa” y completá sus pasos."); Button({ vm.next(OnboardingStep.CONTROL_PREPARE_PC) }, Modifier.fillMaxWidth()) { Text("YA LO PREPARÉ") } }
            OnboardingStep.CONTROL_PREPARE_PC -> { Heading("Preparar la PC"); Text("Instalá MGGX PC Agent. En el celular que queda en casa, escaneá el código que muestre el Agent y terminá esa vinculación."); Button({ vm.next(OnboardingStep.CONTROL_PAIR_HOME) }, Modifier.fillMaxWidth()) { Text("PC VINCULADA AL CELULAR DE CASA") } }
            OnboardingStep.CONTROL_PAIR_HOME -> HomePairingStep(vm)
            OnboardingStep.CONTROL_SUNSHINE -> { Heading("Preparar Sunshine"); Text("Sunshine permite que Moonlight muestre y controle tu PC. Instalalo, abrilo y creá un usuario y una contraseña. Guardalos: los usarás al ingresar el PIN de Moonlight."); Button({ vm.next(OnboardingStep.CONTROL_MOONLIGHT_LAN) }, Modifier.fillMaxWidth()) { Text("YA CONFIGURÉ SUNSHINE") } }
            OnboardingStep.CONTROL_MOONLIGHT_LAN -> MoonlightLanStep(settings, vm)
            OnboardingStep.CONTROL_MOONLIGHT_TAILSCALE -> MoonlightTailscaleStep(settings, vm)
            OnboardingStep.VERIFY -> VerificationStep(vm)
            else -> LaunchedEffect(Unit) { vm.next(OnboardingStep.CONTROL_PREPARE_PHONE) }
        }
    }
}

@Composable private fun HomeWizard(settings: NextSettings, vm: NextViewModel) {
    val context = LocalContext.current
    WizardFrame("Configuración del celular de casa", settings.role, settings.step) {
        when (settings.step) {
            OnboardingStep.HOME_PREPARE_TAILSCALE -> { Heading("Preparar Tailscale"); AppRequirement("Tailscale", "Usá la misma cuenta que en tu celular principal.", TAILSCALE_PACKAGE, context); Button({ vm.next(OnboardingStep.HOME_ALWAYS_ON_VPN) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") } }
            OnboardingStep.HOME_ALWAYS_ON_VPN -> { Heading("Mantené activa la conexión segura"); Text("En Ajustes > VPN > Tailscale activá VPN siempre activa. No actives Bloquear conexiones sin VPN."); OutlinedButton({ open(context, Intent(Settings.ACTION_VPN_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("ABRIR AJUSTES DE VPN") }; Button({ vm.next(OnboardingStep.HOME_BATTERY) }, Modifier.fillMaxWidth()) { Text("YA LO HICE") } }
            OnboardingStep.HOME_BATTERY -> { Heading("Evitar que Android pause la conexión"); Text("Elegí Sin restricciones para MGGX PC Control 2 y revisá también la batería de Tailscale."); OutlinedButton({ requestBatteryOptimization(context) }, Modifier.fillMaxWidth()) { Text("PERMITIR PARA MGGX") }; Button({ vm.next(OnboardingStep.HOME_PAIR_PC) }, Modifier.fillMaxWidth()) { Text("CONTINUAR") } }
            OnboardingStep.HOME_PAIR_PC -> AgentPairingStep(vm)
            OnboardingStep.HOME_PAIR_CONTROL -> HomeControllerOfferStep(settings, vm)
            else -> LaunchedEffect(Unit) { vm.next(OnboardingStep.HOME_PREPARE_TAILSCALE) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WizardFrame(title: String, role: DeviceRole, step: OnboardingStep, content: @Composable () -> Unit) {
    val progress = OnboardingFlow.progress(role, step)
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { item { AssistChip(onClick = {}, label = { Text("Paso ${progress.current} de ${progress.total}") }) }; item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } } } }
}

@Composable private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
@Composable private fun AppRequirement(name: String, explanation: String, packageName: String, context: Context) { val isInstalled = remember(packageName) { installed(context, packageName) }; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(explanation); Text(if (isInstalled) "INSTALADO ✓" else "Todavía no está instalado"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ openStore(context, packageName) }) { Text("INSTALAR") }; Button({ openPackage(context, packageName) }) { Text("ABRIR") } } } } }

@Composable private fun HomePairingStep(vm: NextViewModel) {
    var manual by remember { mutableStateOf(false) }; var payload by remember { mutableStateOf("") }; var localError by remember { mutableStateOf<String?>(null) }
    Heading("Vincular tu celular de uso cotidiano"); Text("En el celular que queda en casa, tocá Mostrar código. Después escanealo desde acá.")
    PairingScannerLauncher(PairingQrKind.HOME_PHONE) { valid -> (valid as? ValidatedQr.Home)?.let { vm.claimHome(it.offer) } }
    OutlinedButton({ manual = !manual }, Modifier.fillMaxWidth()) { Text("INGRESAR CÓDIGO MANUALMENTE") }
    if (manual) { Text("Alternativa sin cámara: pegá el código MGGX completo que muestra el celular de casa."); TextField(payload, { payload = it }, Modifier.fillMaxWidth(), label = { Text("Código MGGX") }); Button({ when (val parsed = PairingProtocol.parse(payload)) { is PairingParseResult.Valid -> if (parsed.offer.role == DeviceRole.HOME_PHONE) vm.claimHome(parsed.offer) else localError = "El código corresponde a otra función"; is PairingParseResult.Invalid -> localError = parsed.reason } }, enabled = payload.isNotBlank()) { Text("VINCULAR") }; localError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
}

@Composable private fun AgentPairingStep(vm: NextViewModel) {
    var fallback by remember { mutableStateOf(false) }; var payload by remember { mutableStateOf("") }; var localError by remember { mutableStateOf<String?>(null) }; var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }; var mac by remember { mutableStateOf("") }; var broadcast by remember { mutableStateOf("") }
    Heading("Vincular tu PC"); Text("En MGGX PC Agent 1.1 elegí Vincular celular de casa. Escaneá el código temporal que aparece en la PC.")
    PairingScannerLauncher(PairingQrKind.PC_AGENT, "ESCANEAR CÓDIGO DE LA PC") { valid -> (valid as? ValidatedQr.Pc)?.let { vm.pairPc(it.offer) } }
    OutlinedButton({ fallback = !fallback }, Modifier.fillMaxWidth()) { Text("MI AGENT TODAVÍA NO MUESTRA UN CÓDIGO") }
    if (fallback) { Text("Tu versión de MGGX PC Agent todavía no permite vinculación automática. Para Agent 1.0 podés completar temporalmente estos datos desde su panel. La app comprobará /health y la autorización antes de guardarlos."); TextField(payload, { payload = it }, Modifier.fillMaxWidth(), label = { Text("Código MGGX completo, si lo tenés") }); if (payload.isNotBlank()) Button({ when (val parsed = PcPairingProtocol.parse(payload)) { is PcPairingParseResult.Valid -> vm.pairPc(parsed.offer); is PcPairingParseResult.Invalid -> localError = parsed.reason } }) { Text("VINCULAR CON CÓDIGO") }; TextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Dirección del Agent (temporal)") }); TextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Credencial del Agent") }); TextField(mac, { mac = it }, Modifier.fillMaxWidth(), label = { Text("MAC Ethernet") }); TextField(broadcast, { broadcast = it }, Modifier.fillMaxWidth(), label = { Text("Dirección de red para encendido") }); Button({ vm.configurePcLegacy(url, token, mac, broadcast) }, Modifier.fillMaxWidth(), enabled = listOf(url, token, mac, broadcast).all(String::isNotBlank)) { Text("COMPROBAR Y ACTIVAR") }; localError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
}

@Composable private fun HomeControllerOfferStep(settings: NextSettings, vm: NextViewModel) {
    val offerState by vm.homeOfferState.collectAsStateWithLifecycle()
    val runtime by vm.homeRuntime.collectAsStateWithLifecycle()
    val activeOffer = offerState.offer
    var remaining by remember(activeOffer?.secret) { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { vm.startHome(); vm.ensureHomeOffer() }
    LaunchedEffect(activeOffer?.secret, offerState.phase) {
        val countedOffer = activeOffer ?: return@LaunchedEffect
        if (offerState.phase != HomeOfferPhase.ACTIVE) return@LaunchedEffect
        while (true) {
            val countdown = com.mggx.pccontrol.next.home.offerCountdown(countedOffer.expiresAtEpochMs, System.currentTimeMillis())
            remaining = countdown.remainingSeconds
            if (countdown.expired) { vm.markHomeOfferExpired(countedOffer.secret); break }
            delay(1_000)
        }
    }
    Heading("Vincular tu celular de uso cotidiano"); Text("Mostrá este código al celular principal. La dirección se obtuvo automáticamente de Tailscale y la credencial es temporal y de un solo uso.")
    if (runtime.state == HomeRuntimeState.ERROR) {
        Text(
            runtime.lastError ?: "La conexión de casa no pudo iniciarse. Podés reintentar sin cerrar la app.",
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(vm::restartHome, Modifier.fillMaxWidth()) { Text("REINTENTAR CONEXIÓN") }
    }
    when (offerState.phase) {
        HomeOfferPhase.ACTIVE -> activeOffer?.let { current -> PairingQr(current.qrUri()); Text("Código alternativo: ${current.humanCode()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Vence en ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}") }
        HomeOfferPhase.EXPIRED -> Text("El código venció. Generá otro para continuar.", color = MaterialTheme.colorScheme.error)
        HomeOfferPhase.CONSUMED -> Text("El celular principal quedó vinculado ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        HomeOfferPhase.ERROR -> Text(offerState.message ?: "No se pudo generar el código.", color = MaterialTheme.colorScheme.error)
        HomeOfferPhase.EMPTY -> Text("Preparando un código seguro…")
    }
    Button(vm::generateHomeOffer, Modifier.fillMaxWidth()) { Text("GENERAR OTRO CÓDIGO") }; Button(vm::complete, Modifier.fillMaxWidth()) { Text("FINALIZAR CONFIGURACIÓN") }; Text("PC vinculada: ${settings.home.agentName}", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun MoonlightLanStep(settings: NextSettings, vm: NextViewModel) { val context = LocalContext.current; val clipboard = LocalClipboardManager.current; val ip = settings.home.lanIp; Heading("Primero conectaremos Moonlight dentro de tu casa"); AddressBlock("IP 1 — Red local", ip, clipboard); Text("Abrí Moonlight, tocá +, pegá esta dirección y tocá Agregar. Moonlight mostrará un PIN: no cierres esa pantalla. En la PC abrí la notificación de Sunshine, iniciá sesión con el usuario y contraseña que creaste, ingresá el PIN, elegí un nombre para este celular y guardá."); OutlinedButton({ openPackage(context, MOONLIGHT_PACKAGE) }, Modifier.fillMaxWidth(), enabled = ip.isNotBlank()) { Text("ABRIR MOONLIGHT") }; Button({ vm.next(OnboardingStep.CONTROL_MOONLIGHT_TAILSCALE) }, Modifier.fillMaxWidth(), enabled = ip.isNotBlank()) { Text("YA VINCULÉ LA IP LOCAL") } }
@Composable private fun MoonlightTailscaleStep(settings: NextSettings, vm: NextViewModel) { val context = LocalContext.current; val clipboard = LocalClipboardManager.current; val ip = settings.home.tailscaleIp; Heading("Ahora configuraremos el acceso desde fuera de casa"); Text("NO ELIMINES LA CONEXIÓN ANTERIOR.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); AddressBlock("IP 2 — Tailscale", ip, clipboard); Text("Abrí Moonlight, tocá + nuevamente, pegá esta segunda dirección y agregala."); OutlinedButton({ openPackage(context, MOONLIGHT_PACKAGE) }, Modifier.fillMaxWidth(), enabled = ip.isNotBlank()) { Text("ABRIR MOONLIGHT") }; Button({ vm.next(OnboardingStep.VERIFY) }, Modifier.fillMaxWidth(), enabled = ip.isNotBlank()) { Text("CONTINUAR") } }
@Composable private fun AddressBlock(label: String, ip: String, clipboard: androidx.compose.ui.platform.ClipboardManager) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label); Text(if (ip.isBlank()) "La PC todavía no informó esta dirección" else ip, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Button({ clipboard.setText(AnnotatedString(ip)) }, enabled = ip.isNotBlank()) { Text("COPIAR IP") } } } }

@Composable private fun VerificationStep(vm: NextViewModel) { val context = LocalContext.current; val checks by vm.verification.collectAsStateWithLifecycle(); LaunchedEffect(Unit) { vm.runVerification(installed(context, TAILSCALE_PACKAGE), vpnActive(context)) }; Heading("Prueba final"); Text("Cada resultado se obtiene de una comprobación real."); checks.forEach { VerificationRow(it) }; Button({ vm.runVerification(installed(context, TAILSCALE_PACKAGE), vpnActive(context)) }, Modifier.fillMaxWidth()) { Text("VOLVER A COMPROBAR") }; Button(vm::complete, Modifier.fillMaxWidth(), enabled = checks.all { it.state == CheckState.SUCCESS }) { Text("IR A MI PC") } }
@Composable private fun VerificationRow(item: VerificationItem) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.label); if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall) }; when (item.state) { CheckState.PENDING -> Text("○"); CheckState.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp)); CheckState.SUCCESS -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); CheckState.FAILURE -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) } }

@Composable private fun ControlDashboard(settings: NextSettings, vm: NextViewModel) {
    val context = LocalContext.current; val info by vm.pcInfo.collectAsStateWithLifecycle(); val busy by vm.busy.collectAsStateWithLifecycle(); var confirm by remember { mutableStateOf<PowerAction?>(null) }
    LaunchedEffect(Unit) { vm.refresh(); vm.events.collect { if (it is UiEvent.OpenMoonlight) openPackage(context, MOONLIGHT_PACKAGE) } }
    DashboardShell(settings.pairedPcName, Icons.Default.Computer) { AnimatedContent(info?.state ?: PcState.UNKNOWN, label = "pc-state") { Text(it.name.replace('_', ' '), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }; StatusRow("Celular en casa", if (settings.pairedHomeHost.isBlank()) "No vinculado" else "Vinculado"); StatusRow("MGGX PC Agent", status(info?.agent?.reachable)); StatusRow("Sunshine", status(info?.sunshine?.running)); StatusRow("Tailscale PC", status(info?.tailscale?.running)); Button(vm::openPc, Modifier.fillMaxWidth(), enabled = busy == null) { if (busy == "open_pc") CircularProgressIndicator(Modifier.size(20.dp)); Text(" ABRIR PC") }; if (info?.state != PcState.ONLINE) Button({ vm.command(PowerAction.WAKE) }, Modifier.fillMaxWidth(), enabled = busy == null) { Text("PRENDER PC") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ confirm = PowerAction.SHUTDOWN }, Modifier.weight(1f), enabled = busy == null) { Text("APAGAR") }; Button({ confirm = PowerAction.RESTART }, Modifier.weight(1f), enabled = busy == null) { Text("REINICIAR") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ vm.command(PowerAction.LOCK) }, Modifier.weight(1f), enabled = busy == null) { Text("BLOQUEAR") }; OutlinedButton(vm::restartSunshine, Modifier.weight(1f), enabled = busy == null) { Text("SUNSHINE") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ confirm = PowerAction.SLEEP }, Modifier.weight(1f), enabled = busy == null) { Text("SUSPENDER") }; OutlinedButton({ confirm = PowerAction.HIBERNATE }, Modifier.weight(1f), enabled = busy == null) { Text("HIBERNAR") } }; OutlinedButton(vm::refresh, Modifier.fillMaxWidth(), enabled = busy == null) { Text(if (busy == "refresh") "ACTUALIZANDO…" else "ACTUALIZAR") }; LegacyCard(settings, vm) }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("¿${action.name.lowercase().replaceFirstChar { it.uppercase() }} ${settings.pairedPcName}?") }, text = { Text("La conexión remota puede cerrarse.") }, dismissButton = { TextButton({ confirm = null }) { Text("CANCELAR") } }, confirmButton = { Button({ confirm = null; vm.command(action) }) { Text("CONFIRMAR") } }) }
}

@Composable private fun HomeDashboard(settings: NextSettings, vm: NextViewModel) { val runtime by vm.homeRuntime.collectAsState(); val offerState by vm.homeOfferState.collectAsStateWithLifecycle(); DashboardShell("Este celular mantiene tu PC disponible", Icons.Default.Home) { Text("Dejalo conectado al cargador y al Wi‑Fi."); StatusRow("Servicio", if (runtime.serverRunning) "Activo ✓" else "Detenido"); StatusRow("Wi‑Fi", if (runtime.wifiAvailable) "Conectado" else "Sin conexión"); StatusRow("Tailscale", if (runtime.vpnActive) "VPN activa" else "Revisar"); StatusRow("PC", runtime.state.name.replace('_', ' ')); StatusRow("MGGX PC Agent", status(runtime.agentReachable)); Button(vm::restartHome, Modifier.fillMaxWidth()) { Text("REINICIAR CONEXIÓN") }; Button(vm::generateHomeOffer, Modifier.fillMaxWidth()) { Text("MOSTRAR CÓDIGO PARA VINCULAR OTRO CELULAR") }; if (offerState.phase == HomeOfferPhase.ACTIVE) offerState.offer?.let { PairingQr(it.qrUri()); Text("Código ${it.humanCode()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }; offerState.message?.let { Text(it) }; Text("PC vinculada: ${settings.home.agentName}") } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DashboardShell(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) = Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { Icon(icon, null, Modifier.padding(12.dp)) }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } } } }
@Composable private fun StatusRow(name: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(value, fontWeight = FontWeight.Medium) }
private fun status(value: Boolean?) = when (value) { true -> "Online ✓"; false -> "No disponible"; null -> "Sin datos" }
@Composable private fun LegacyCard(settings: NextSettings, vm: NextViewModel) { var show by remember { mutableStateOf(false) }; var url by remember { mutableStateOf(settings.legacyUrl) }; var token by remember { mutableStateOf("") }; var pcId by remember { mutableStateOf(settings.legacyPcId) }; OutlinedButton({ show = !show }, Modifier.fillMaxWidth()) { Text("AVANZADO / COMPATIBILIDAD") }; if (show) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Configuración manual temporal para el sistema actual.", fontWeight = FontWeight.Bold); TextField(url, { url = it }, label = { Text("Dirección del Relay") }); TextField(token, { token = it }, label = { Text("Token") }); TextField(pcId, { pcId = it }, label = { Text("ID de PC") }); Button({ vm.saveLegacy(url, pcId, token) }) { Text("GUARDAR") } } } }

@Suppress("DEPRECATION") private fun installed(context: Context, packageName: String) = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
private fun vpnActive(context: Context): Boolean { val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false; return manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } }
private fun openPackage(context: Context, packageName: String) = runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
private fun openStore(context: Context, packageName: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
private fun open(context: Context, intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
private fun requestBatteryOptimization(context: Context) = runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
