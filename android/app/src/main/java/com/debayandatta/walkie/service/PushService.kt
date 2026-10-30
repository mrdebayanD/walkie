package com.debayandatta.walkie.service

import com.debayandatta.walkie.core.Notifications
import com.debayandatta.walkie.core.Walkie
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** High-priority FCM data messages from the server (see docs/PROTOCOL.md). */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "tx" -> Walkie.wakeForTalk()
            "sos" -> {
                val id = d["sosId"] ?: return
                Notifications.sos(this, id, d["sender"] ?: "Family")
                Walkie.openSos(id)
            }
            "sos_resolved" -> {
                val id = d["sosId"] ?: return
                Notifications.sosResolved(this, id, d["sender"] ?: "Family", d["resolution"] == "false_alarm")
            }
        }
    }

    override fun onNewToken(token: String) {
        Walkie.onNewPushToken(token)
    }
}
