package com.mggx.pccontrol.next.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mggx.pccontrol.next.R
import com.mggx.pccontrol.next.data.NextSettingsStore
import com.mggx.pccontrol.next.security.CredentialResult
import com.mggx.pccontrol.next.v2.HomeRuntimeSnapshot
import com.mggx.pccontrol.next.v2.HomeRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HomeDeviceRuntime {
    private val _state = MutableStateFlow(HomeRuntimeSnapshot())
    val state: StateFlow<HomeRuntimeSnapshot> = _state.asStateFlow()
    internal fun publish(snapshot: HomeRuntimeSnapshot) { _state.value = snapshot }
}

/** Persistent only for the home-device role. The control phone never starts this service. */
class HomeDeviceService : Service() {
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> fail("service_coroutine", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var store: NextSettingsStore? = null
    private var server: HomeDeviceServer? = null
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var initialized = false
    private val lifecycle = Mutex()

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (!stage("foreground") {
                createChannel()
                val type = homeForegroundServiceType(Build.VERSION.SDK_INT)
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Iniciando conexión…"), type)
            }) return stopAfterInitFailure()
        val createdStore = stageValue("settings_store") { NextSettingsStore(applicationContext) }
            ?: return stopAfterInitFailure()
        store = createdStore
        server = stageValue("server_create") { HomeDeviceServer(createdStore) }
            ?: return stopAfterInitFailure()
        stage("wake_lock") {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "mggx:home-connection",
            )?.apply { setReferenceCounted(false); acquire() }
        }
        initialized = true
        requestEnsureServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_RESTART -> requestRestartServer()
            else -> requestEnsureServer()
        }
        // BootReceiver/WorkManager restore explicitly. Sticky restarts turned recoverable failures
        // into a persistent process crash loop on some devices.
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        watchdog?.cancel()
        runCatching { runBlocking { lifecycle.withLock { server?.stop() } } }.onFailure { fail("server_destroy", it) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }.onFailure { fail("wake_lock_release", it) }
        val previous = HomeDeviceRuntime.state.value
        HomePairingCoordinator.invalidateForUnavailableServer("El servidor local se detuvo. Tocá Reintentar conexión.")
        HomeDeviceRuntime.publish(homeSnapshot(HomeRuntimeState.STOPPED, false, false, previous.serverPort, networkState(this)))
        scope.cancel()
        super.onDestroy()
    }

    private fun requestEnsureServer() = scope.launch { lifecycle.withLock { ensureServerRunningLocked() } }
    private fun requestRestartServer() = scope.launch { lifecycle.withLock { restartServerLocked() } }

    /** The only lifecycle entry points. Start/stop/restart cannot overlap. */
    private suspend fun ensureServerRunningLocked() {
        val knownPort = store?.snapshot()?.home?.port
        publishServer(HomeRuntimeState.STARTING, false, false, knownPort)
        val currentStore = store ?: return fail("settings_store", IllegalStateException("Store unavailable"))
        val currentServer = server ?: return fail("server_create", IllegalStateException("Server unavailable"))
        val config = try {
            currentStore.snapshot().home
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            fail("settings_read", error)
            return
        }
        if (!config.enabled) { currentServer.stop(); publishServer(HomeRuntimeState.STOPPED, false, false, config.port); stopSelf(); return }
        try {
            currentServer.start(config)
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            fail("server_start", error, "No se pudo iniciar la conexión. El puerto puede estar ocupado.")
            return
        }
        val network = networkState(this)
        publishServer(HomeRuntimeState.READY, true, true, config.port, network)
        watchdog?.cancel()
        watchdog = scope.launch { monitor() }
    }

    private suspend fun restartServerLocked() {
        watchdog?.cancel()
        val current = server
        try {
            current?.stop()
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            fail("server_stop", error)
        }
        HomePairingCoordinator.invalidateForUnavailableServer("La conexión se está reiniciando. Generá el código nuevamente cuando esté lista.")
        ensureServerRunningLocked()
    }

    private suspend fun monitor() {
        while (true) {
            val network = networkState(this)
            val config = store?.snapshot()?.home ?: return
            val currentServer = server
            val base = when {
                currentServer?.isRunning != true || !currentServer.localHealth(config.port) -> {
                    HomePairingCoordinator.invalidateForUnavailableServer("El servidor local se detuvo. Tocá Reintentar conexión.")
                    homeSnapshot(HomeRuntimeState.ERROR, false, false, config.port, network, lastError = currentServer?.error ?: "El servidor local no responde")
                }
                !network.wifiAvailable -> homeSnapshot(HomeRuntimeState.NETWORK_UNAVAILABLE, true, true, config.port, network)
                !network.tailscaleAvailable -> homeSnapshot(HomeRuntimeState.TAILSCALE_UNAVAILABLE, true, true, config.port, network)
                else -> checkAgent(config, network)
            }
            HomeDeviceRuntime.publish(base)
            updateNotification(base)
            delay(30_000)
        }
    }

    private suspend fun checkAgent(config: com.mggx.pccontrol.next.v2.HomeDeviceConfig, network: HomeNetworkState): HomeRuntimeSnapshot {
        val token = store?.readAgentToken() ?: CredentialResult.Missing
        if (config.agentUrl.isBlank()) return homeSnapshot(HomeRuntimeState.PC_OFFLINE, true, true, config.port, network, null, "Falta vincular la PC")
        if (token !is CredentialResult.Value) return homeSnapshot(HomeRuntimeState.AGENT_AUTH_ERROR, true, true, config.port, network, false, "Volvé a vincular la PC")
        return runCatching {
            val reply = HttpAgentGateway(config.agentUrl, token.value).status()
            when (reply.code) {
                401, 403 -> homeSnapshot(HomeRuntimeState.AGENT_AUTH_ERROR, true, true, config.port, network, false, "La autorización con la PC venció")
                in 200..299 -> {
                    val offline = JSONObject(reply.body).optString("state").equals("offline", true)
                    homeSnapshot(if (offline) HomeRuntimeState.PC_OFFLINE else HomeRuntimeState.PC_ONLINE, true, true, config.port, network, !offline)
                }
                else -> homeSnapshot(HomeRuntimeState.ERROR, true, true, config.port, network, false, "La PC respondió con un error")
            }
        }.getOrElse { homeSnapshot(HomeRuntimeState.PC_OFFLINE, true, true, config.port, network, false, "No se pudo contactar la PC") }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.home_channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("MGGX PC Control").setContentText(message).setOngoing(true).build()
    private fun updateNotification(snapshot: HomeRuntimeSnapshot) {
        val text = when (snapshot.state) { HomeRuntimeState.PC_ONLINE -> "Tu PC está disponible para acceso remoto"; HomeRuntimeState.PC_OFFLINE -> "Conexión activa · PC apagada"; HomeRuntimeState.TAILSCALE_UNAVAILABLE -> "Tailscale necesita atención"; HomeRuntimeState.NETWORK_UNAVAILABLE -> "Esperando conexión Wi‑Fi"; HomeRuntimeState.AGENT_AUTH_ERROR -> "Volvé a vincular la PC"; HomeRuntimeState.ERROR -> "La conexión necesita atención"; else -> "Conexión con tu PC activa" }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun stage(name: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (error: Throwable) {
        if (error.isFatal()) throw error
        fail(name, error); false
    }

    private fun <T> stageValue(name: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        if (error.isFatal()) throw error
        fail(name, error); null
    }

    private fun stopAfterInitFailure() {
        initialized = false
        stopSelf()
    }

    private fun fail(stage: String, error: Throwable, userMessage: String = "La conexión de casa no pudo iniciarse. Abrí la app para reintentar.") {
        val failure = HomeServiceFailureLog.record(applicationContext, stage, error)
        HomeDeviceRuntime.publish(
            HomeRuntimeSnapshot(
                state = HomeRuntimeState.ERROR,
                serverRunning = false,
                serverState = com.mggx.pccontrol.next.v2.HomeServerState.ERROR,
                lastError = "$userMessage (${failure.exceptionType.substringAfterLast('.')})",
            ),
        )
        HomePairingCoordinator.invalidateForUnavailableServer(userMessage)
    }
    private fun publishServer(state: HomeRuntimeState, running: Boolean, localHealth: Boolean, port: Int?, network: HomeNetworkState = networkState(this)) =
        HomeDeviceRuntime.publish(homeSnapshot(state, running, localHealth, port, network))

    companion object { private const val CHANNEL = "mggx_pc_control2_home"; private const val NOTIFICATION_ID = 2042; private const val ACTION_START = "com.mggx.pccontrol.next.START_HOME"; private const val ACTION_RESTART = "com.mggx.pccontrol.next.RESTART_HOME"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java).setAction(ACTION_START))
        fun restart(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java).setAction(ACTION_RESTART))
    }
}

class HomeDeviceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val settings = NextSettingsStore(context).snapshot()
            if (settings.home.enabled) runCatching { HomeDeviceService.start(context) }.getOrElse { HomeRestoreWorker.enqueue(context) }
            pending.finish()
        }
    }
}

class HomeRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!shouldRetryHomeRestore(runAttemptCount)) return Result.failure()
        return try {
            HomeDeviceService.start(applicationContext)
            delay(2_000)
            if (HomeDeviceRuntime.state.value.state == HomeRuntimeState.ERROR) Result.failure() else Result.success()
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            HomeServiceFailureLog.record(applicationContext, "restore_worker", error)
            if (!shouldRetryHomeRestore(runAttemptCount + 1)) Result.failure() else Result.retry()
        }
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            "mggx_pc_control2_restore_home",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HomeRestoreWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}

internal data class HomeNetworkState(val wifiAvailable: Boolean, val tailscaleAvailable: Boolean, val tailscaleIp: String?)

private fun networkState(context: Context): HomeNetworkState {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return HomeNetworkState(false, false, null)
    // When a VPN is active it becomes activeNetwork, so inspect all transports for the physical Wi‑Fi.
    val networks = manager.allNetworks.mapNotNull(manager::getNetworkCapabilities)
    val wifi = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    val tailnetIp = TailscaleAddressProvider.address()
    val vpn = tailnetIp != null || networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    return HomeNetworkState(wifi, vpn, tailnetIp)
}

private fun homeSnapshot(
    state: HomeRuntimeState,
    running: Boolean,
    health: Boolean,
    port: Int?,
    network: HomeNetworkState,
    agentReachable: Boolean? = null,
    lastError: String? = null,
) = HomeRuntimeSnapshot(
    state = state,
    serverRunning = running,
    // The PC/Agent may report an error while the local home server is still healthy.
    // Keep these layers separate so a reachable server does not look stopped merely
    // because the PC is offline or rejected an Agent request.
    serverState = when {
        running && health -> com.mggx.pccontrol.next.v2.HomeServerState.READY
        state == HomeRuntimeState.STARTING -> com.mggx.pccontrol.next.v2.HomeServerState.STARTING
        state == HomeRuntimeState.ERROR -> com.mggx.pccontrol.next.v2.HomeServerState.ERROR
        else -> com.mggx.pccontrol.next.v2.HomeServerState.STOPPED
    },
    localHealth = health,
    serverPort = port,
    tailscaleIp = network.tailscaleIp,
    wifiAvailable = network.wifiAvailable,
    vpnActive = network.tailscaleAvailable,
    agentReachable = agentReachable,
    lastError = lastError,
)

private fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath

internal fun homeForegroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

internal fun shouldRetryHomeRestore(runAttemptCount: Int): Boolean = runAttemptCount < 3
