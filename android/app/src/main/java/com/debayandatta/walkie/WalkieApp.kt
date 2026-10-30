package com.debayandatta.walkie

import android.app.Application
import com.debayandatta.walkie.core.Notifications
import com.debayandatta.walkie.core.Walkie

class WalkieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Walkie.init(this)
    }
}
