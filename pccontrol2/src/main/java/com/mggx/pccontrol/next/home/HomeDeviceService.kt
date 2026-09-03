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
        scope.launch { startConfiguredServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) return START_NOT_STICKY
        if (intent?.action == ACTION_RESTART) scope.launch {
            watchdog?.cancel()
            runCatching { server?.stop() }.onFailure { fail("server_stop", it) }
            startConfiguredServer()
        }
        // BootReceiver/WorkManager restore explicitly. Sticky restarts turned recoverable failures
        // into a persistent process crash loop on some devices.
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        watchdog?.cancel()
        runCatching { server?.stop() }.onFailure { fail("server_destroy", it) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }.onFailure { fail("wake_lock_release", it) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startConfiguredServer() {
        val currentStore = store ?: return fail("settings_store", IllegalStateException("Store unavailable"))
        val currentServer = server ?: return fail("server_create", IllegalStateException("Server unavailable"))
        val config = try {
            currentStore.snapshot().home
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            fail("settings_read", error)
            return
        }
        if (!config.enabled) { stopSelf(); return }
        try {
            currentServer.start(config)
        } catch (error: Throwable) {
            if (error.isFatal()) throw error
            fail("server_start", error, "No se pudo iniciar la conexión. El puerto puede estar ocupado.")
            return
        }
        watchdog = scope.launch { monitor() }
    }

    private suspend fun monitor() {
        while (true) {
            val network = networkState(this)
            val config = store?.snapshot()?.home ?: return
            val currentServer = server
            val base = when {
                currentServer?.isRunning != true -> HomeRuntimeSnapshot(HomeRuntimeState.ERROR, false, network.first, network.second, lastError = "Servicio detenido")
                !network.first -> HomeRuntimeSnapshot(HomeRuntimeState.NETWORK_UNAVAILABLE, true, false, network.second)
                !network.second -> HomeRuntimeSnapshot(HomeRuntimeState.TAILSCALE_UNAVAILABLE, true, true, false)
                else -> checkAgent(config, network.first, network.second)
            }
            HomeDeviceRuntime.publish(base)
            updateNotification(base)
            delay(30_000)
        }
    }

    private suspend fun checkAgent(config: com.mggx.pccontrol.next.v2.HomeDeviceConfig, wifi: Boolean, vpn: Boolean): HomeRuntimeSnapshot {
        val token = store?.readAgentToken() ?: CredentialResult.Missing
        if (config.agentUrl.isBlank()) return HomeRuntimeSnapshot(HomeRuntimeState.PC_OFFLINE, true, wifi, vpn, null, "Falta vincular la PC")
        if (token !is CredentialResult.Value) return HomeRuntimeSnapshot(HomeRuntimeState.AGENT_AUTH_ERROR, true, wifi, vpn, false, "Volvé a vincular la PC")
        return runCatching {
            val reply = HttpAgentGateway(config.agentUrl, token.value).status()
            when (reply.code) {
                401, 403 -> HomeRuntimeSnapshot(HomeRuntimeState.AGENT_AUTH_ERROR, true, wifi, vpn, false, "La autorización con la PC venció")
                in 200..299 -> {
                    val offline = JSONObject(reply.body).optString("state").equals("offline", true)
                    HomeRuntimeSnapshot(if (offline) HomeRuntimeState.PC_OFFLINE else HomeRuntimeState.PC_ONLINE, true, wifi, vpn, !offline)
                }
                else -> HomeRuntimeSnapshot(HomeRuntimeState.ERROR, true, wifi, vpn, false, "La PC respondió con un error")
            }
        }.getOrElse { HomeRuntimeSnapshot(HomeRuntimeState.PC_OFFLINE, true, wifi, vpn, false, "No se pudo contactar la PC") }
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
                lastError = "$userMessage (${failure.exceptionType.substringAfterLast('.')})",
            ),
        )
    }
    companion object { private const val CHANNEL = "mggx_pc_control2_home"; private const val NOTIFICATION_ID = 2042; private const val ACTION_RESTART = "com.mggx.pccontrol.next.RESTART_HOME"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java))
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

private fun networkState(context: Context): Pair<Boolean, Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false to false
    val active = manager.activeNetwork?.let(manager::getNetworkCapabilities)
    val wifi = active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val vpn = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    return wifi to vpn
}

private fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath

internal fun homeForegroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

internal fun shouldRetryHomeRestore(runAttemptCount: Int): Boolean = runAttemptCount < 3
