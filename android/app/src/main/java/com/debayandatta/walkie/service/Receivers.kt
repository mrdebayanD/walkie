package com.debayandatta.walkie.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debayandatta.walkie.core.Notifications
import com.debayandatta.walkie.core.Walkie

/**
 * After a restart Android doesn't allow starting a microphone service from
 * the background, so we post a reminder instead. Pushes still wake the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Walkie.prefs.token != null && Walkie.prefs.onDuty && !Walkie.prefs.lowPower) {
            Notifications.bootReminder(context)
        }
    }
}

class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            OFF_DUTY -> Walkie.setOnDuty(false)
            RESPOND -> {
                val id = intent.getStringExtra("sosId") ?: return
                Walkie.respond(id)
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
            }
        }
    }

    companion object {
        const val OFF_DUTY = "com.debayandatta.walkie.OFF_DUTY"
        const val RESPOND = "com.debayandatta.walkie.RESPOND"
    }
}
