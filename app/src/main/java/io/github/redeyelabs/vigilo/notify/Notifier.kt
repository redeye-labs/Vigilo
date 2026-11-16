package io.github.redeyelabs.vigilo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.redeyelabs.vigilo.MainActivity
import io.github.redeyelabs.vigilo.R
import io.github.redeyelabs.vigilo.data.Prefs

object Notifier {
    /** Canale allarmi precedente, senza suono esplicito: viene eliminato all'avvio. */
    const val CH_ALARM_LEGACY = "vigilo_alarm"
    /** Allarmi Sentinella con suono di notifica (volume multimedia/notifiche). */
    const val CH_ALARM = "vigilo_alarm_v2"
    /** Allarmi Sentinella sul flusso sveglia, anche con il telefono in silenzioso. */
    const val CH_ALARM_LOUD = "vigilo_alarm_loud"
    const val CH_STATUS = "vigilo_status"
    const val CH_SERVICE = "vigilo_service"
    const val SERVICE_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_ALARM_LEGACY)

        val notifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: notifSound
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, ctx.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = ctx.getString(R.string.channel_alarm_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    setSound(notifSound, notifAttrs)
                    enableLights(true)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM_LOUD, ctx.getString(R.string.channel_alarm_loud), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = ctx.getString(R.string.channel_alarm_loud_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    setSound(alarmSound, alarmAttrs)
                    setBypassDnd(true)
                    enableLights(true)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_service_desc) }
        )
    }

    fun isAlarmChannel(channel: String): Boolean =
        channel == CH_ALARM || channel == CH_ALARM_LOUD || channel == CH_ALARM_LEGACY

    /** Sceglie il canale allarme in base all'opzione «Suona anche in silenzioso». */
    fun resolveChannel(ctx: Context, channel: String): String {
        if (!isAlarmChannel(channel)) return channel
        return if (Prefs.get(ctx).alarmBypassSilent) CH_ALARM_LOUD else CH_ALARM
    }

    fun openAppIntent(ctx: Context, tab: String? = null, requestCode: Int = tab.hashCode()): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            tab?.let { putExtra("tab", it) }
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun show(ctx: Context, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val actual = resolveChannel(ctx, channel)
        val alarm = isAlarmChannel(actual)
        val content = openAppIntent(ctx, "storico", requestCode = 1001)
        val builder = NotificationCompat.Builder(ctx, actual)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (alarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(content)
            .setAutoCancel(true)
        if (alarm) {
            builder.setFullScreenIntent(openAppIntent(ctx, "storico", requestCode = 1002), true)
        }
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }

    fun serviceNotification(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setContentTitle("Vigilo sta sorvegliando l'auto")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(ctx, requestCode = 1))
            .build()
}
