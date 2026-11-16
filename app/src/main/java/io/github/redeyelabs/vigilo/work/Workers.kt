package io.github.redeyelabs.vigilo.work

import android.content.BroadcastReceiver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.api.FleetTelemetrySupport
import io.github.redeyelabs.vigilo.api.TeslaApi
import io.github.redeyelabs.vigilo.api.VehicleCommandSupport
import io.github.redeyelabs.vigilo.data.AppDatabase
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import io.github.redeyelabs.vigilo.notify.Notifier
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Controllo periodico (minimo 15 minuti, limite di Android). */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Monitor.runOnce(applicationContext)
        AppDatabase.get(applicationContext).events().deleteOlderThan(System.currentTimeMillis() - 90L * 24 * 3600_000)
        return Result.success()
    }
}

/** Esegue una regola della programmazione settimanale e ripianifica la successiva. */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val db = AppDatabase.get(applicationContext)
        val rule = db.schedules().byId(id) ?: return Result.success()
        if (!rule.active) return Result.success()
        val p = Prefs.get(applicationContext)
        val what = if (rule.enableSentry) "Attivazione" else "Disattivazione"
        val res = runCatching {
            p.expectedSentryChangeUntil = System.currentTimeMillis() + 30 * 60_000L
            if (p.backendMode) Backend(p).command(if (rule.enableSentry) "sentry_on" else "sentry_off")
            else TeslaApi(p).setSentry(p.vin, rule.enableSentry)
        }
        if (res.isSuccess) {
            db.events().insert(EventEntity(type = EventType.SCHEDULE, title = "$what Sentinella programmata", message = "Eseguita alle %02d:%02d".format(rule.hour, rule.minute)))
            Notifier.show(applicationContext, Notifier.CH_STATUS, "Sentinella ${if (rule.enableSentry) "attivata" else "disattivata"}", "Programmazione delle %02d:%02d eseguita".format(rule.hour, rule.minute))
        } else {
            val msg = res.exceptionOrNull()?.let { VehicleCommandSupport.userMessage(it) } ?: "errore"
            if (runAttemptCount < 2) return Result.retry()
            db.events().insert(EventEntity(type = EventType.ERROR, title = "$what Sentinella non riuscita", message = msg))
            Notifier.show(applicationContext, Notifier.CH_STATUS, "$what Sentinella non riuscita", msg)
        }
        Scheduler.scheduleRule(applicationContext, rule, afterNow = LocalDateTime.now().plusMinutes(1))
        return Result.success()
    }
}


/** Rinnovo quotidiano della configurazione avvisi Sentinella (best effort, in carica o su Wi-Fi). */
class TelemetryRenewWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val p = Prefs.get(applicationContext)
        if (!p.telemetryAutoRenew) return Result.success()
        // Con Home Assistant il rinnovo lo fa il componente aggiuntivo.
        if (p.backendMode) return Result.success()
        if (!p.isLoggedIn || p.vin.isBlank() || p.lastEnrolledJwsHash.isBlank()) return Result.success()
        if (!chargingOrWifi(applicationContext)) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        val url = p.telemetryJwsUrl.ifBlank { FleetTelemetrySupport.defaultJwsUrl(p.proxyUrl) }
        if (url.isBlank()) return Result.success()
        return try {
            val api = TeslaApi(p)
            val msg = api.autoRenewSentryAlerts(p.vin, url)
            if (!msg.isNullOrBlank()) {
                Notifier.show(
                    applicationContext,
                    Notifier.CH_STATUS,
                    "Avvisi Sentinella",
                    msg,
                )
            }
            Result.success()
        } catch (e: Exception) {
            val text = e.message ?: "Rinnovo non riuscito"
            Notifier.show(applicationContext, Notifier.CH_STATUS, "Rinnovo avvisi Sentinella non riuscito", text)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        fun chargingOrWifi(ctx: Context): Boolean {
            val charging = ctx.getSystemService(BatteryManager::class.java)?.isCharging == true
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                (cm != null && !cm.isActiveNetworkMetered)
            return FleetTelemetrySupport.shouldRunRenewNow(charging, wifi)
        }
    }
}

object Scheduler {
    private const val POLL = "vigilo_poll"
    private const val TEL_RENEW = "vigilo_telemetry_renew"

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun applyPolling(ctx: Context) {
        val p = Prefs.get(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!p.pollingEnabled) { wm.cancelUniqueWork(POLL); return }
        val req = PeriodicWorkRequestBuilder<PollWorker>(p.pollMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(POLL, ExistingPeriodicWorkPolicy.UPDATE, req)
    }


    fun applyTelemetryRenew(ctx: Context) {
        val p = Prefs.get(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!p.telemetryAutoRenew) { wm.cancelUniqueWork(TEL_RENEW); return }
        val req = PeriodicWorkRequestBuilder<TelemetryRenewWorker>(1, TimeUnit.DAYS)
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(TEL_RENEW, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun applyIntensive(ctx: Context) {
        val p = Prefs.get(ctx)
        val i = Intent(ctx, MonitorService::class.java)
        if (p.intensiveEnabled) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
    }

    /** Prossima occorrenza della regola dopo [afterNow]. */
    fun nextOccurrence(rule: ScheduleEntity, afterNow: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (rule.daysMask == 0) return null
        val t = LocalTime.of(rule.hour, rule.minute)
        for (d in 0..7) {
            val day = afterNow.toLocalDate().plusDays(d.toLong())
            val bit = 1 shl (day.dayOfWeek.value - 1) // lunedì = bit0
            if (rule.daysMask and bit == 0) continue
            val dt = day.atTime(t)
            if (dt.isAfter(afterNow)) return dt
        }
        return null
    }

    fun scheduleRule(ctx: Context, rule: ScheduleEntity, afterNow: LocalDateTime = LocalDateTime.now()) {
        val wm = WorkManager.getInstance(ctx)
        val name = "vigilo_sched_${rule.id}"
        val next = if (rule.active) nextOccurrence(rule, afterNow) else null
        if (next == null) { wm.cancelUniqueWork(name); return }
        val delay = Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .setInputData(workDataOf("id" to rule.id))
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelRule(ctx: Context, id: Long) = WorkManager.getInstance(ctx).cancelUniqueWork("vigilo_sched_$id")

    suspend fun rescheduleAll(ctx: Context) {
        AppDatabase.get(ctx).schedules().all().forEach { scheduleRule(ctx, it) }
    }

    fun dayLabel(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "Lun"; DayOfWeek.TUESDAY -> "Mar"; DayOfWeek.WEDNESDAY -> "Mer"
        DayOfWeek.THURSDAY -> "Gio"; DayOfWeek.FRIDAY -> "Ven"; DayOfWeek.SATURDAY -> "Sab"; DayOfWeek.SUNDAY -> "Dom"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Scheduler.applyPolling(context)
        Scheduler.applyTelemetryRenew(context)
        runBlocking { Scheduler.rescheduleAll(context) }
        if (Prefs.get(context).intensiveEnabled) Scheduler.applyIntensive(context)
    }
}
