package io.github.redeyelabs.vigilo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.redeyelabs.vigilo.MainActivity
import io.github.redeyelabs.vigilo.R
import io.github.redeyelabs.vigilo.data.Prefs

object Notifier {
    /** Canali allarme delle versioni precedenti: eliminati all'avvio (Android non permette di cambiarne il suono). */
    private val LEGACY_CHANNELS = listOf("vigilo_alarm", "vigilo_alarm_v2", "vigilo_alarm_loud", "vigilo_status")
    /** Allarme vero (Panic): sirena di Vigilo, volume notifiche. */
    const val CH_PANIC = "vigilo_panic_v1"
    /** Qualcuno vicino all'auto (Aware) ed eventi sospetti: doppio bip, volume notifiche. */
    const val CH_AWARE = "vigilo_aware_v1"
    /** «Suona anche in silenzioso»: canale senza suono proprio, il suono lo riproduce [AlarmPlayer] sul flusso sveglia. */
    const val CH_ALARM_LOUD = "vigilo_alarm_loud_v2"
    /** Avvisi e problemi: tono discreto. */
    const val CH_INFO = "vigilo_info_v1"
    const val CH_SERVICE = "vigilo_service"

    /** Alias per chi pubblica: "allarme" (il tipo sceglie suono e canale) e "avviso". */
    const val CH_ALARM = "vigilo_alarm"
    const val CH_STATUS = CH_INFO
    const val SERVICE_ID = 1

    private const val GROUP_ALERTS = "vigilo.alerts"
    private const val GROUP_INFO = "vigilo.info"
    const val GROUP_SERVICE = "vigilo.service"

    private val VIBRATION = longArrayOf(0, 400, 200, 400)
    private val VIBRATION_PANIC = longArrayOf(0, 700, 250, 700, 250, 700)

    fun soundUri(ctx: Context, kind: AlertKind): Uri =
        Uri.parse("android.resource://${ctx.packageName}/${SoundPlan.rawRes(kind)}")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }

        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_PANIC, ctx.getString(R.string.channel_panic), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_panic_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION_PANIC
                setSound(soundUri(ctx, AlertKind.PANIC), notifAttrs)
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AWARE, ctx.getString(R.string.channel_aware), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_aware_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(soundUri(ctx, AlertKind.AWARE), notifAttrs)
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM_LOUD, ctx.getString(R.string.channel_alarm_loud), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_alarm_loud_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION_PANIC
                setSound(null, null) // suono riprodotto da AlarmPlayer sul flusso sveglia: niente doppioni
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.channel_status_desc)
                setSound(soundUri(ctx, AlertKind.INFO), notifAttrs)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_service_desc); setSound(null, null) }
        )
    }

    fun isAlarmChannel(channel: String): Boolean =
        channel == CH_ALARM || channel == CH_PANIC || channel == CH_AWARE || channel == CH_ALARM_LOUD ||
            channel in LEGACY_CHANNELS && channel != "vigilo_status"

    /**
     * Problema che impedirebbe di sentire gli avvisi di questo tipo (notifiche spente, canale silenzioso
     * o senza suono). Null se è tutto a posto.
     */
    fun soundProblem(ctx: Context, kind: AlertKind): String? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return "Le notifiche di Vigilo sono spente."
        val id = SoundPlan.channelFor(kind, Prefs.get(ctx).alarmBypassSilent)
        val ch = nm.getNotificationChannel(id) ?: return null
        if (ch.importance == NotificationManager.IMPORTANCE_NONE) return "La categoria «${ch.name}» è disattivata."
        if (id == CH_ALARM_LOUD) {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            if (am.getStreamVolume(android.media.AudioManager.STREAM_ALARM) == 0) return "Il volume della sveglia è a zero."
            return null
        }
        if (ch.importance < NotificationManager.IMPORTANCE_DEFAULT) return "La categoria «${ch.name}» è impostata come silenziosa."
        if (ch.sound == null) return "La categoria «${ch.name}» non ha un suono."
        return null
    }

    /** Apre le impostazioni di sistema della categoria usata per questo tipo di avviso. */
    fun channelSettingsIntent(ctx: Context, kind: AlertKind): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, SoundPlan.channelFor(kind, Prefs.get(ctx).alarmBypassSilent))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Notifica di prova con lo stesso percorso degli avvisi veri. False se mancano i permessi. */
    fun test(ctx: Context, kind: AlertKind): Boolean {
        if (!canPost(ctx)) return false
        val (title, text) = when (kind) {
            AlertKind.PANIC -> "Prova: allarme" to "Così suona un allarme della Sentinella."
            AlertKind.AWARE -> "Prova: qualcuno vicino all'auto" to "Così suona un avviso «Aware»."
            AlertKind.INFO -> "Prova: avviso" to "Così suonano gli avvisi e i problemi."
        }
        show(ctx, if (kind == AlertKind.INFO) CH_INFO else CH_ALARM, title, text, kind)
        return true
    }

    private fun canPost(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(ctx: Context, tab: String? = null, requestCode: Int = tab.hashCode()): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            tab?.let { putExtra("tab", it) }
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Pubblica un avviso. Per gli allarmi ([CH_ALARM]) [kind] sceglie suono e canale (Aware se non indicato);
     * con «Suona anche in silenzioso» il suono lo riproduce anche [AlarmPlayer] sul flusso sveglia.
     */
    fun show(ctx: Context, channel: String, title: String, text: String, kind: AlertKind? = null) {
        if (!canPost(ctx)) return
        val alarm = isAlarmChannel(channel)
        val k = if (alarm) (kind?.takeIf { it != AlertKind.INFO } ?: AlertKind.AWARE) else AlertKind.INFO
        val actual = SoundPlan.channelFor(k, Prefs.get(ctx).alarmBypassSilent)
        val content = openAppIntent(ctx, "storico", requestCode = 1001)
        val car = Prefs.get(ctx).displayVehicleName()
        val builder = NotificationCompat.Builder(ctx, actual)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setSubText(car.ifBlank { null })
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (alarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(
                when (k) {
                    AlertKind.PANIC -> NotificationCompat.CATEGORY_ALARM
                    AlertKind.AWARE -> NotificationCompat.CATEGORY_EVENT
                    AlertKind.INFO -> NotificationCompat.CATEGORY_STATUS
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(content)
            .setAutoCancel(true)
            // Gruppo diverso da quello della notifica fissa: ogni avviso suona da sé.
            .setGroup(if (alarm) GROUP_ALERTS else GROUP_INFO)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
        if (k == AlertKind.PANIC) {
            builder.setFullScreenIntent(openAppIntent(ctx, "storico", requestCode = 1002), true)
        }
        // ContextCompat gestisce anche Android < 13 (lì vale «notifiche attive»).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
        }
        if (actual == CH_ALARM_LOUD) AlarmPlayer.play(ctx, k)
    }

    fun serviceNotification(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setContentTitle(Prefs.get(ctx).displayVehicleName().let { if (it.isBlank()) "Vigilo sta sorvegliando l'auto" else "Vigilo sorveglia $it" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setGroup(GROUP_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(ctx, requestCode = 1))
            .build()
}
