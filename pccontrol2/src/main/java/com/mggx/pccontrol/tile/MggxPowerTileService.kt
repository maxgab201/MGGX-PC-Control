package com.mggx.pccontrol.next.tile
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.mggx.pccontrol.next.MainActivity
import com.mggx.pccontrol.next.data.*
import com.mggx.pccontrol.next.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@RequiresApi(24)
class MggxPowerTileService:TileService(){
    private val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onClick(){super.onClick();serviceScope.launch{val store=SettingsStore(this@MggxPowerTileService);val s=store.settings.first();val api=if(s.demoMode)DemoRelayApi({s.simulatedStatus},{})else when(val config=store.loadRelayConfig(s)){is RelayResult.Success->HttpRelayApi(config.value);is RelayResult.Failure->{qsTile?.state=Tile.STATE_INACTIVE;qsTile?.updateTile();return@launch}};val status=api.getStatus();if(status is RelayResult.Success&&status.value.state==PcState.ONLINE)withContext(Dispatchers.Main){openApp()}else api.wake();qsTile?.state=Tile.STATE_ACTIVE;qsTile?.updateTile()}}

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
    override fun onDestroy(){serviceScope.cancel();super.onDestroy()}
}
