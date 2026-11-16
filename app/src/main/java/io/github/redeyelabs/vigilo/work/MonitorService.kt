package io.github.redeyelabs.vigilo.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitoraggio "intensivo" in primo piano: controlla ogni N minuti (1..30), più spesso del limite di
 * 15 minuti di WorkManager. Mostra una notifica fissa (obbligatoria per Android). Ogni lettura di
 * vehicle_data è a pagamento secondo il listino Fleet API: usare con giudizio.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.serviceNotification(this, "Avvio…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.SERVICE_ID, n)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            val fmt = SimpleDateFormat("HH:mm", Locale.ITALY)
            while (isActive) {
                val p = Prefs.get(this@MonitorService)
                if (!p.intensiveEnabled) { stopSelf(); break }
                val r = Monitor.runOnce(this@MonitorService)
                val txt = r.fold(
                    onSuccess = { s ->
                        val sentry = when (s?.sentry) { true -> "Sentinella ON"; false -> "Sentinella OFF"; null -> "Sentinella ?" }
                        val conn = if (s?.online == true) "online" else (s?.connectionState ?: "?")
                        "$sentry · ${s?.batteryLevel ?: "?"}% · $conn · ${fmt.format(Date())}"
                    },
                    onFailure = { "Errore: ${it.message}" },
                )
                runCatching {
                    NotificationManagerCompat.from(this@MonitorService)
                        .notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@MonitorService, txt))
                }
                delay(p.intensiveMinutes * 60_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
