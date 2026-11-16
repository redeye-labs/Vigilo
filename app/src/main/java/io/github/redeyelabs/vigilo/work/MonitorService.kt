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
 * Servizio in primo piano con due usi:
 * - monitoraggio intensivo (modalità diretta): legge lo stato ogni N minuti;
 * - notifiche allarme dall'app (modalità Home Assistant): interroga gli eventi dell'add-on
 *   ogni [BACKEND_ALARM_POLL_MS], così un Aware/Panic può fare suonare Vigilo anche a schermo spento.
 *
 * Mostra una notifica fissa (obbligatoria per Android).
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
                val pollAlarms = Scheduler.shouldPollBackendAlarms(p)
                if (!p.intensiveEnabled && !pollAlarms) { stopSelf(); break }
                val txt = if (pollAlarms) {
                    val r = runCatching { Monitor.syncBackendEvents(this@MonitorService, p) }
                    r.fold(
                        onSuccess = { "In ascolto degli allarmi da Home Assistant · ${fmt.format(Date())}" },
                        onFailure = { "Errore: ${it.message}" },
                    )
                } else {
                    val r = Monitor.runOnce(this@MonitorService)
                    r.fold(
                        onSuccess = { s ->
                            val sentry = when (s?.sentry) { true -> "Sentinella ON"; false -> "Sentinella OFF"; null -> "Sentinella ?" }
                            val conn = if (s?.online == true) "online" else (s?.connectionState ?: "?")
                            "$sentry · ${s?.batteryLevel ?: "?"}% · $conn · ${fmt.format(Date())}"
                        },
                        onFailure = { "Errore: ${it.message}" },
                    )
                }
                runCatching {
                    NotificationManagerCompat.from(this@MonitorService)
                        .notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@MonitorService, txt))
                }
                val wait = if (pollAlarms) BACKEND_ALARM_POLL_MS else p.intensiveMinutes * 60_000L
                delay(wait)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val BACKEND_ALARM_POLL_MS = 18_000L
    }
}
