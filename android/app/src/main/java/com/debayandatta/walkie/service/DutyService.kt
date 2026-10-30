package com.debayandatta.walkie.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.debayandatta.walkie.core.Notifications
import com.debayandatta.walkie.core.Walkie

/**
 * Keeps the process alive while on duty, with an ongoing notification.
 * It does no work itself (no polling, no wake locks): the single relay
 * connection and the audio threads live in [Walkie] and only run when
 * someone talks.
 *
 * Types: started from the app with the microphone type (so hands-free keeps
 * working with the screen locked); started from a push with media playback
 * only (Android doesn't allow the mic type from the background, and
 * listening doesn't need it).
 */
class DutyService : Service() {
    private var hasMic = false
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantMic = intent?.getBooleanExtra(EXTRA_MIC, false) == true && Walkie.hasMic()
        // Call startForeground on every start: Android requires it after each
        // startForegroundService(). Types only ever widen (never drop the mic).
        run {
            hasMic = hasMic || wantMic
            var types = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            val n = Notifications.duty(this, "Listening for your family")
            try {
                ServiceCompat.startForeground(this, Notifications.ID_DUTY, n, types)
            } catch (e: Exception) {
                // e.g. the mic type isn't allowed right now: fall back to playback only.
                hasMic = false
                runCatching {
                    ServiceCompat.startForeground(this, Notifications.ID_DUTY, n,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
                }.onFailure { stopSelf(); return START_NOT_STICKY }
            }
            running = true
        }
        // If Android kills us, don't restart from the background (the mic type
        // couldn't be restored); the user reopens the app or a push wakes us.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MIC = "mic"
        @Volatile var isRunning = false
            private set

        fun start(ctx: Context, withMic: Boolean) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DutyService::class.java).putExtra(EXTRA_MIC, withMic))
                isRunning = true
            }
        }

        fun stop(ctx: Context) {
            isRunning = false
            ctx.stopService(Intent(ctx, DutyService::class.java))
        }
    }
}
