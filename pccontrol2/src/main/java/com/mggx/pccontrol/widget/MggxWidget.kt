package com.mggx.pccontrol.next.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mggx.pccontrol.next.MainActivity
import com.mggx.pccontrol.next.data.DemoRelayApi
import com.mggx.pccontrol.next.data.HttpRelayApi
import com.mggx.pccontrol.next.data.SettingsStore
import com.mggx.pccontrol.next.domain.DemoStatus
import com.mggx.pccontrol.next.domain.PcState
import com.mggx.pccontrol.next.domain.RelayResult
import kotlinx.coroutines.flow.first

class MggxWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){provideContent{val size=LocalSize.current;val compact=size.width<180.dp;Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF171A21))).padding(14.dp),verticalAlignment=Alignment.Vertical.CenterVertically){Text("MGGX PC",style=TextStyle(color=ColorProvider(Color.White)));Text("● CONTROL",style=TextStyle(color=ColorProvider(Color(0xFF8EA2FF))));Spacer(GlanceModifier.height(8.dp));Row(horizontalAlignment=Alignment.Horizontal.CenterHorizontally){Text("⚡ PRENDER",GlanceModifier.clickable(actionRunCallback<WakeAction>()).padding(8.dp),TextStyle(color=ColorProvider(Color.White)));if(!compact){Spacer(GlanceModifier.width(10.dp));Text("ABRIR",GlanceModifier.clickable(actionStartActivity(Intent(context,MainActivity::class.java))).padding(8.dp),TextStyle(color=ColorProvider(Color.White)))}}}}}}
class PowerWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=MggxWidget()}
class StatusWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=MggxWidget()}
class ControlWidgetReceiver:GlanceAppWidgetReceiver(){override val glanceAppWidget=MggxWidget()}

class WakeAction:ActionCallback{override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){val store=SettingsStore(context);val settings=store.settings.first();val api=if(settings.demoMode)DemoRelayApi({settings.simulatedStatus},{})else when(val config=store.loadRelayConfig(settings)){is RelayResult.Success->HttpRelayApi(config.value);is RelayResult.Failure->{MggxWidget().update(context,glanceId);return}};val status=api.getStatus();if(status is RelayResult.Success&&status.value.state==PcState.ONLINE){context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}else api.wake();MggxWidget().update(context,glanceId)}}
