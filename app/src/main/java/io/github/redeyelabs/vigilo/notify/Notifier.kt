package io.github.redeyelabs.vigilo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.redeyelabs.vigilo.MainActivity
import io.github.redeyelabs.vigilo.R

object Notifier {
    const val CH_ALARM = "vigilo_alarm"
    const val CH_STATUS = "vigilo_status"
    const val CH_SERVICE = "vigilo_service"
    const val SERVICE_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, ctx.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.channel_alarm_desc); enableVibration(true) }
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

    fun openAppIntent(ctx: Context, tab: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            tab?.let { putExtra("tab", it) }
        }
        return PendingIntent.getActivity(ctx, tab.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun show(ctx: Context, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (channel == CH_ALARM) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (channel == CH_ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(ctx, "storico"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    fun serviceNotification(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vigilo)
            .setContentTitle("Vigilo sta sorvegliando l'auto")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
}
