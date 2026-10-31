package com.debayandatta.walkie.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** REST client for the Walkie server. All calls are main-safe. */
class Api(val baseUrl: String, var token: String?) {

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): ByteArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path)
        token?.let { b.header("Authorization", "Bearer $it") }
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "GET" || method == "DELETE" -> null
            else -> "{}".toRequestBody(JSON)
        }
        b.method(method, rb)
        http.newCall(b.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val o = runCatching { JSONObject(String(bytes)) }.getOrNull()
                throw ApiException(resp.code, o?.optString("error") ?: "http_${resp.code}", o?.optString("message")?.ifEmpty { null } ?: "The server answered ${resp.code}.")
            }
            bytes
        }
    }

    private suspend fun json(method: String, path: String, body: JSONObject? = null) = JSONObject(String(call(method, path, body)))

    suspend fun join(code: String, name: String, phone: String): Pair<String, Member> {
        val o = json("POST", "v1/join", JSONObject().put("code", code).put("name", name).put("phone", phone).put("platform", "android"))
        return o.getString("token") to Member.from(o.getJSONObject("user"))
    }

    suspend fun me(): Pair<Member, Settings> = parseMe(meRaw())
    /** Raw JSON, so the app can keep a copy and open offline. */
    suspend fun meRaw(): String = String(call("GET", "v1/me"))
    suspend fun channelsRaw(): String = String(call("GET", "v1/channels"))

    suspend fun updateMe(name: String?, phone: String?): Member {
        val b = JSONObject()
        name?.let { b.put("name", it) }
        phone?.let { b.put("phone", it) }
        return Member.from(json("PUT", "v1/me", b).getJSONObject("user"))
    }

    suspend fun setFcmToken(token: String) { call("PUT", "v1/me/push", JSONObject().put("fcm", token)) }

    suspend fun channels(): List<Channel> = parseChannels(channelsRaw())
    suspend fun users(): List<Member> = json("GET", "v1/users").optJSONArray("users").objects().map(Member::from)
    suspend fun approve(id: String) { call("POST", "v1/users/$id/approve") }

    /** Opens (or returns) the personal channel with another member. */
    suspend fun personalChannel(userId: String): Channel =
        Channel.from(json("POST", "v1/direct", JSONObject().put("userId", userId)).getJSONObject("channel"))

    /** Admin: fix someone's name or phone number. */
    suspend fun updateUser(id: String, name: String?, phone: String?) {
        val b = JSONObject()
        name?.let { b.put("name", it) }
        phone?.let { b.put("phone", it) }
        call("PUT", "v1/users/$id", b)
    }
    suspend fun removeUser(id: String) { call("DELETE", "v1/users/$id") }

    suspend fun createChannel(name: String, color: String, memberIds: List<String>): Channel =
        Channel.from(json("POST", "v1/channels", JSONObject().put("name", name).put("color", color).put("memberIds", JSONArray(memberIds))).getJSONObject("channel"))

    suspend fun updateChannel(id: String, name: String?, color: String?) {
        val b = JSONObject()
        name?.let { b.put("name", it) }
        color?.let { b.put("color", it) }
        call("PATCH", "v1/channels/$id", b)
    }

    suspend fun setMembers(id: String, memberIds: List<String>) { call("PUT", "v1/channels/$id/members", JSONObject().put("memberIds", JSONArray(memberIds))) }
    suspend fun deleteChannel(id: String) { call("DELETE", "v1/channels/$id") }

    suspend fun createInvite(channelId: String, label: String): InviteResult {
        val o = json("POST", "v1/invites", JSONObject().put("channelId", channelId).put("label", label))
        return InviteResult(o.getString("code"), o.getString("url"), o.getJSONObject("invite").optString("channelId"))
    }

    suspend fun invites(): List<OpenInvite> = json("GET", "v1/invites").optJSONArray("invites").objects().map {
        OpenInvite(it.getString("id"), it.optString("channelId"), it.optString("label"), parseTime(it.optString("expiresAt")) ?: java.time.Instant.now())
    }

    suspend fun deleteInvite(id: String) { call("DELETE", "v1/invites/$id") }
    suspend fun clips(channelId: String): List<Clip> = json("GET", "v1/channels/$channelId/clips").optJSONArray("clips").objects().map(Clip::from)
    suspend fun clipAudio(id: String): ByteArray = call("GET", "v1/clips/$id/audio")
    suspend fun setClipTtl(hours: Int): Settings = Settings.from(json("PUT", "v1/settings", JSONObject().put("clipTtlHours", hours)).optJSONObject("settings"))

    suspend fun sendSos(lat: Double?, lng: Double?, accuracy: Double?): SosEvent {
        val b = JSONObject()
        if (lat != null && lng != null) b.put("lat", lat).put("lng", lng).put("accuracy", accuracy ?: 0.0)
        return SosEvent.from(json("POST", "v1/sos", b).getJSONObject("sos"))
    }

    suspend fun sosList(): List<SosEvent> = json("GET", "v1/sos").optJSONArray("sos").objects().map(SosEvent::from)
    suspend fun sosLocation(id: String, lat: Double, lng: Double, accuracy: Double) {
        call("POST", "v1/sos/$id/location", JSONObject().put("lat", lat).put("lng", lng).put("accuracy", accuracy))
    }
    suspend fun sosAck(id: String) { call("POST", "v1/sos/$id/ack") }
    suspend fun sosRespond(id: String) { call("POST", "v1/sos/$id/respond") }
    suspend fun sosResolve(id: String, falseAlarm: Boolean) {
        call("POST", "v1/sos/$id/resolve", JSONObject().put("resolution", if (falseAlarm) "false_alarm" else "safe"))
    }
}

fun parseMe(raw: String): Pair<Member, Settings> {
    val o = JSONObject(raw)
    return Member.from(o.getJSONObject("user")) to Settings.from(o.optJSONObject("settings"))
}

fun parseChannels(raw: String): List<Channel> = JSONObject(raw).optJSONArray("channels").objects().map(Channel::from)
