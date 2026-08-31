package com.mggx.pccontrol.next.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
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

object HomeDeviceRuntime {
    private val _state = MutableStateFlow(HomeRuntimeSnapshot())
    val state: StateFlow<HomeRuntimeSnapshot> = _state.asStateFlow()
    internal fun publish(snapshot: HomeRuntimeSnapshot) { _state.value = snapshot }
}

/** Persistent only for the home-device role. The control phone never starts this service. */
class HomeDeviceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: NextSettingsStore
    private lateinit var server: HomeDeviceServer
    private var watchdog: Job? = null

    override fun onCreate() {
        super.onCreate(); store = NextSettingsStore(applicationContext); server = HomeDeviceServer(store)
        createChannel(); startForeground(NOTIFICATION_ID, notification("Iniciando conexión…"))
        scope.launch {
            val config = store.snapshot().home
            if (!config.enabled) { stopSelf(); return@launch }
            runCatching { server.start(config) }.onFailure { HomeDeviceRuntime.publish(HomeRuntimeSnapshot(HomeRuntimeState.ERROR, lastError = "No se pudo iniciar el servicio")) }
            watchdog = launch { monitor() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { watchdog?.cancel(); server.stop(); scope.cancel(); HomeDeviceRuntime.publish(HomeRuntimeSnapshot()); super.onDestroy() }

    private suspend fun monitor() {
        while (true) {
            val network = networkState(this)
            val config = store.snapshot().home
            val base = when {
                !server.isRunning -> HomeRuntimeSnapshot(HomeRuntimeState.ERROR, false, network.first, network.second, lastError = "Servicio detenido")
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
        val token = store.readAgentToken()
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
    companion object { private const val CHANNEL = "mggx_pc_control2_home"; private const val NOTIFICATION_ID = 2042
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HomeDeviceService::class.java))
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
    override suspend fun doWork(): Result = runCatching { HomeDeviceService.start(applicationContext); Result.success() }.getOrElse { Result.retry() }
    companion object { fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("mggx_pc_control2_restore_home", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HomeRestoreWorker>().build()) }
}

private fun networkState(context: Context): Pair<Boolean, Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false to false
    val active = manager.activeNetwork?.let(manager::getNetworkCapabilities)
    val wifi = active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val vpn = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    return wifi to vpn
}
