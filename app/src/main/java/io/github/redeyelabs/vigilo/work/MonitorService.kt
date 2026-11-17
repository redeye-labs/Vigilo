package io.github.redeyelabs.vigilo.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.notify.Notifier
import io.github.redeyelabs.vigilo.notify.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano con due usi:
 * - monitoraggio intensivo (modalità diretta): legge lo stato ogni N minuti;
 * - notifiche allarme dall'app (modalità Home Assistant): interroga gli eventi dell'add-on
 *   ogni [BACKEND_ALARM_POLL_MS], così un Aware/Panic può fare suonare Vigilo anche a schermo spento.
 *
 * Mostra una notifica fissa e silenziosa con batteria, autonomia e stato della Sentinella.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastText: String? = null
    private var lastBattery: Int? = null
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = Monitor.lastSnapshot(Prefs.get(this))
        postService(
            ServiceStatus.text(initial) ?: ServiceStatus.FALLBACK_TEXT,
            initial?.batteryLevel,
            force = true,
            foreground = true,
        )
        // Riavvii ripetuti (apertura dell'app, aggiornamento, riavvio): un solo ciclo attivo.
        // Dopo un cambio di impostazioni (restart) il ciclo riparte subito con i nuovi intervalli.
        if (loop?.isActive == true && intent?.getBooleanExtra(EXTRA_RESTART_LOOP, false) != true) return START_STICKY
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val p = Prefs.get(this@MonitorService)
                val pollAlarms = Scheduler.shouldPollBackendAlarms(p)
                if (!p.intensiveEnabled && !pollAlarms) { stopSelf(); break }
                // runOnce in modalità backend aggiorna anche lo snapshot (batteria, km, Sentinella)
                // e importa gli eventi nuovi per le notifiche allarme.
                val r = Monitor.runOnce(this@MonitorService)
                if (r.isSuccess) {
                    val s = r.getOrNull() ?: Monitor.lastSnapshot(p)
                    postService(ServiceStatus.text(s) ?: ServiceStatus.FALLBACK_TEXT, s?.batteryLevel)
                } else if (lastText == null) {
                    postService(ServiceStatus.FALLBACK_TEXT, null)
                }
                val wait = if (pollAlarms) BACKEND_ALARM_POLL_MS else p.intensiveMinutes * 60_000L
                delay(wait)
            }
        }
        return START_STICKY
    }

    /**
     * Aggiorna la notifica fissa solo se testo o batteria sono cambiati, così non ripete
     * suono o animazione. [foreground] va usato all'avvio (startForeground obbligatorio).
     */
    private fun postService(text: String, battery: Int?, force: Boolean = false, foreground: Boolean = false) {
        if (!force && text == lastText && battery == lastBattery) return
        lastText = text
        lastBattery = battery
        val n = Notifier.serviceNotification(this, text)
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Notifier.SERVICE_ID, n)
        } else {
            runCatching {
                NotificationManagerCompat.from(this).notify(Notifier.SERVICE_ID, n)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val BACKEND_ALARM_POLL_MS = 18_000L
        const val EXTRA_RESTART_LOOP = "restart_loop"
    }
}
