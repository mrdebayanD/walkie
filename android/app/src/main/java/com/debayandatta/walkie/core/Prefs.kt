package com.debayandatta.walkie.core

import android.content.Context

/** Small local settings. The token is private to the app's sandbox. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("walkie", Context.MODE_PRIVATE)

    var server: String?
        get() = p.getString("server", null)
        set(v) = p.edit().putString("server", v).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var onDuty: Boolean
        get() = p.getBoolean("onDuty", true)
        set(v) = p.edit().putBoolean("onDuty", v).apply()
    /** Low-power: no always-on connection; wake by push and play from history. */
    var lowPower: Boolean
        get() = p.getBoolean("lowPower", false)
        set(v) = p.edit().putBoolean("lowPower", v).apply()
    var speakerMuted: Boolean
        get() = p.getBoolean("speakerMuted", false)
        set(v) = p.edit().putBoolean("speakerMuted", v).apply()
    var selectedChannel: String?
        get() = p.getString("selectedChannel", null)
        set(v) = p.edit().putString("selectedChannel", v).apply()
    var played: Set<String>
        get() = p.getStringSet("played", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("played", v.toList().takeLast(2000).toSet()).apply()
    var batteryGuideDone: Boolean
        get() = p.getBoolean("batteryGuideDone", false)
        set(v) = p.edit().putBoolean("batteryGuideDone", v).apply()
    var fcmToken: String?
        get() = p.getString("fcmToken", null)
        set(v) = p.edit().putString("fcmToken", v).apply()

    /** Last good copies from the server, so the app opens even when it's unreachable. */
    var cacheMe: String?
        get() = p.getString("cache.me", null)
        set(v) = p.edit().putString("cache.me", v).apply()
    var cacheChannels: String?
        get() = p.getString("cache.channels", null)
        set(v) = p.edit().putString("cache.channels", v).apply()

    fun clearSession() = p.edit().remove("server").remove("token").remove("selectedChannel")
        .remove("cache.me").remove("cache.channels").apply()
}
