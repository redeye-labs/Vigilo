package io.github.redeyelabs.vigilo.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.redeyelabs.vigilo.MainActivity
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.work.Monitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VigiloWidget : GlanceAppWidget() {
    companion object {
        suspend fun refresh(ctx: Context) = VigiloWidget().updateAll(ctx)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Monitor.lastSnapshot(Prefs.get(context))
        val name = Prefs.get(context).vehicleName.ifBlank { "La mia Tesla" }
        provideContent {
            val white = ColorProvider(Color.White)
            val muted = ColorProvider(Color(0xFF8E8E93))
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xFF171717)).cornerRadius(20.dp)
                    .padding(12.dp).clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = TextStyle(color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s?.batteryLevel ?: "--"}%", style = TextStyle(color = white, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.width(10.dp))
                    Column {
                        val sentry = when (s?.sentry) { true -> "Sentinella attiva"; false -> "Sentinella spenta"; null -> "Sentinella ?" }
                        Text(sentry, style = TextStyle(color = white, fontSize = 13.sp))
                        val lock = when (s?.locked) { true -> "Chiusa"; false -> "APERTA"; null -> "—" }
                        Text("$lock · ${s?.rangeKm?.toInt() ?: "--"} km", style = TextStyle(color = white, fontSize = 12.sp))
                    }
                }
                val t = s?.timestamp?.let { SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(it)) } ?: "mai"
                Text("Aggiornato: $t", style = TextStyle(color = muted, fontSize = 11.sp))
            }
        }
    }
}

class VigiloWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VigiloWidget()
}
