package com.debayandatta.walkie.net

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Shapes match docs/PROTOCOL.md. Keep both in sync.

data class Member(
    val id: String,
    val name: String,
    val role: String,
    val status: String,
    val platform: String,
    val phone: String?,
    val online: Boolean,
    val duty: Boolean,
) {
    val isAdmin get() = role == "admin"
    val isPending get() = status == "pending"
    val initials: String get() = initialsOf(name)

    companion object {
        fun from(o: JSONObject) = Member(
            id = o.getString("id"),
            name = o.optString("name"),
            role = o.optString("role", "member"),
            status = o.optString("status", "active"),
            platform = o.optString("platform"),
            phone = o.optString("phone").ifEmpty { null },
            online = o.optBoolean("online"),
            duty = o.optBoolean("duty"),
        )
    }
}

data class Channel(
    val id: String,
    val name: String,
    val color: String,
    val isDefault: Boolean,
    val members: List<Member>,
) {
    companion object {
        fun from(o: JSONObject) = Channel(
            id = o.getString("id"),
            name = o.optString("name"),
            color = o.optString("color", "blue"),
            isDefault = o.optBoolean("isDefault"),
            members = o.optJSONArray("members").objects().map(Member::from),
        )
    }
}

data class Settings(val clipTtlHours: Int) {
    companion object {
        fun from(o: JSONObject?) = Settings(o?.optInt("clipTtlHours", 24) ?: 24)
    }
}

data class Clip(
    val id: String,
    val channelId: String,
    val userId: String,
    val userName: String,
    val mode: String,
    val sosId: String?,
    val startedAt: Instant,
    val durationMs: Long,
    val expiresAt: Instant?,
) {
    companion object {
        fun from(o: JSONObject) = Clip(
            id = o.getString("id"),
            channelId = o.optString("channelId"),
            userId = o.optString("userId"),
            userName = o.optString("userName"),
            mode = o.optString("mode", "hold"),
            sosId = o.optString("sosId").ifEmpty { null },
            startedAt = parseTime(o.optString("startedAt")) ?: Instant.now(),
            durationMs = o.optLong("durationMs"),
            expiresAt = parseTime(o.optString("expiresAt")),
        )
    }
}

data class SosLocation(val lat: Double, val lng: Double, val accuracy: Double, val at: Instant)

data class SosEvent(
    val id: String,
    val userId: String,
    val userName: String,
    val channelId: String,
    val createdAt: Instant,
    val state: String,
    val resolution: String?,
    val location: SosLocation?,
    val recipients: List<String>,
    val acked: List<String>,
    val responders: List<String>,
    val clipIds: List<String>,
) {
    val isActive get() = state == "active"

    companion object {
        fun from(o: JSONObject) = SosEvent(
            id = o.getString("id"),
            userId = o.optString("userId"),
            userName = o.optString("userName"),
            channelId = o.optString("channelId"),
            createdAt = parseTime(o.optString("createdAt")) ?: Instant.now(),
            state = o.optString("state"),
            resolution = o.optString("resolution").ifEmpty { null },
            location = o.optJSONObject("location")?.let {
                SosLocation(it.optDouble("lat"), it.optDouble("lng"), it.optDouble("accuracy"), parseTime(it.optString("at")) ?: Instant.now())
            },
            recipients = o.optJSONArray("recipients").strings(),
            acked = o.optJSONArray("acked").strings(),
            responders = o.optJSONArray("responders").strings(),
            clipIds = o.optJSONArray("clipIds").strings(),
        )
    }
}

data class InviteResult(val code: String, val url: String, val channelId: String)

data class OpenInvite(val id: String, val channelId: String, val label: String, val expiresAt: Instant)

enum class TalkMode(val wire: String, val limitMs: Long) {
    HOLD("hold", 120_000), HANDSFREE("handsfree", 60_000), SOS("sos", 15_000)
}

fun initialsOf(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    if (parts.size >= 2) return (parts[0].take(1) + parts[1].take(1)).uppercase()
    val w = parts.firstOrNull() ?: "?"
    return w.take(if (w.length > 3) 1 else 2).replaceFirstChar { it.uppercase() }
}

fun parseTime(s: String?): Instant? = if (s.isNullOrEmpty()) null else runCatching { Instant.parse(s) }.getOrNull()

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
