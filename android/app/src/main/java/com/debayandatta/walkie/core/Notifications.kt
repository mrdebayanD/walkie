package com.debayandatta.walkie.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.debayandatta.walkie.R
import com.debayandatta.walkie.service.NotificationActions
import com.debayandatta.walkie.ui.MainActivity

object Notifications {
    const val CH_DUTY = "duty"
    const val CH_SOS = "sos"
    const val CH_INFO = "info"
    const val ID_DUTY = 1
    const val ID_BOOT = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DUTY, "On duty", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Hollo keeps listening for your family"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Loud alert when someone in the family sends SOS"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setBypassDnd(true) // only takes effect if the user allows it in settings
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context, extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extra?.let { i.putExtra("sosId", it) }
        return PendingIntent.getActivity(ctx, extra?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun duty(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, NotificationActions::class.java).setAction(NotificationActions.OFF_DUTY), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_DUTY)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("Hollo is on duty")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Go off duty", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun sos(ctx: Context, sosId: String, sender: String) {
        val respond = PendingIntent.getBroadcast(ctx, sosId.hashCode() + 1,
            Intent(ctx, NotificationActions::class.java).setAction(NotificationActions.RESPOND).putExtra("sosId", sosId), PendingIntent.FLAG_IMMUTABLE)
        val open = openApp(ctx, sosId)
        val n = NotificationCompat.Builder(ctx, CH_SOS)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("SOS from $sender")
            .setContentText("Tap to see where they are and call them.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFFF453A.toInt())
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // shows over the lock screen where allowed
            .addAction(0, "I'm responding", respond)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(sosId.hashCode(), n)
    }

    fun sosResolved(ctx: Context, sosId: String, sender: String, falseAlarm: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(sosId.hashCode())
        nm.notify(sosId.hashCode() + 7, NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("SOS closed")
            .setContentText(if (falseAlarm) "$sender's SOS was a false alarm." else "$sender is safe.")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())
    }

    fun bootReminder(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_BOOT, NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("Hollo")
            .setContentText("Tap to go back on duty after the restart.")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())
    }
}
