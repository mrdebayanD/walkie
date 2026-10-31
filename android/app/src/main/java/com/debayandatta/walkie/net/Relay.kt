package com.debayandatta.walkie.net

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

sealed class RelayEvent {
    data object Connected : RelayEvent()
    data object Disconnected : RelayEvent()
    data class FloorGranted(val channel: String, val txId: Long, val maxMs: Long) : RelayEvent()
    data class FloorDenied(val channel: String, val reason: String) : RelayEvent()
    data class FloorTaken(val channel: String, val txId: Long, val userId: String, val name: String, val mode: String) : RelayEvent()
    data class FloorEnded(val channel: String, val txId: Long, val userId: String, val clipId: String?, val reason: String) : RelayEvent()
    data class Presence(val userId: String, val online: Boolean, val duty: Boolean) : RelayEvent()
    data class Sos(val sos: SosEvent) : RelayEvent()
    data object ChannelsChanged : RelayEvent()
    data object UsersChanged : RelayEvent()
    data class SettingsChanged(val settings: Settings) : RelayEvent()
}

/**
 * WebSocket to the server with automatic reconnect.
 * Text events are delivered on the main thread; audio is delivered on the
 * socket thread (in order) so it never waits behind UI work.
 */
class Relay(serverUrl: String, private val token: String) {
    var onEvent: ((RelayEvent) -> Unit)? = null
    var onAudio: ((txId: Long, packets: List<ByteArray>) -> Unit)? = null
    @Volatile var duty = true
    @Volatile var isConnected = false
        private set

    private val url = serverUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/ws"
    private val client: OkHttpClient = Api.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var wanted = false
    private var backoffMs = 1000L
    private val pinger = object : Runnable {
        override fun run() {
            send(JSONObject().put("t", "ping"))
            main.postDelayed(this, 240_000) // keeps NAT mappings alive; server times out at 10 min
        }
    }

    fun connect() {
        main.post {
            wanted = true
            if (ws == null) open()
        }
    }

    fun disconnect() {
        main.post {
            wanted = false
            main.removeCallbacks(pinger)
            ws?.close(1000, null)
            ws = null
            isConnected = false
        }
    }

    /** Call when the network comes back to reconnect right away. */
    fun nudge() {
        main.post { if (wanted && !isConnected) { backoffMs = 1000; ws?.cancel(); ws = null; open() } }
    }

    /** Drops the current socket (possibly frozen by Doze) and reconnects at once. */
    fun reconnectNow() {
        main.post { if (wanted) { backoffMs = 1000; ws?.cancel(); ws = null; isConnected = false; open() } }
    }

    private fun open() {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val socket = client.newWebSocket(req, listener)
        ws = socket
        socket.send(JSONObject().put("t", "hello").put("duty", duty).put("app", "android").toString())
    }

    fun updateDuty(on: Boolean) {
        duty = on
        send(JSONObject().put("t", "duty").put("on", on))
    }

    fun requestFloor(channel: String, mode: TalkMode, sosId: String? = null) {
        val m = JSONObject().put("t", "floor_request").put("channel", channel).put("mode", mode.wire)
        sosId?.let { m.put("sosId", it) }
        send(m)
    }

    fun releaseFloor() = send(JSONObject().put("t", "floor_release"))

    /** 0x01, then for each packet a u16 big-endian length and the bytes. */
    fun sendAudio(packets: List<ByteArray>) {
        val size = 1 + packets.sumOf { 2 + it.size }
        val b = ByteBuffer.allocate(size)
        b.put(1)
        for (p in packets) { b.putShort(p.size.toShort()); b.put(p) }
        ws?.send(b.array().toByteString())
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun emit(e: RelayEvent) = main.post { onEvent?.invoke(e) }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            val ch = m.optString("channel")
            val tx = m.optLong("txId")
            when (m.optString("t")) {
                "welcome" -> main.post {
                    isConnected = true
                    backoffMs = 1000
                    main.removeCallbacks(pinger)
                    main.postDelayed(pinger, 240_000)
                    onEvent?.invoke(RelayEvent.Connected)
                }
                "floor_granted" -> emit(RelayEvent.FloorGranted(ch, tx, m.optLong("maxMs")))
                "floor_denied" -> emit(RelayEvent.FloorDenied(ch, m.optString("reason")))
                "floor_taken" -> emit(RelayEvent.FloorTaken(ch, tx, m.optString("userId"), m.optString("name"), m.optString("mode")))
                "floor_ended" -> emit(RelayEvent.FloorEnded(ch, tx, m.optString("userId"), m.optString("clipId").ifEmpty { null }, m.optString("reason")))
                "presence" -> emit(RelayEvent.Presence(m.optString("userId"), m.optBoolean("online"), m.optBoolean("duty")))
                "sos" -> m.optJSONObject("sos")?.let { emit(RelayEvent.Sos(SosEvent.from(it))) }
                "channels_changed" -> emit(RelayEvent.ChannelsChanged)
                "users_changed" -> emit(RelayEvent.UsersChanged)
                "settings_changed" -> emit(RelayEvent.SettingsChanged(Settings.from(m.optJSONObject("settings"))))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== ws) return
            val d = bytes.toByteArray()
            if (d.size < 6 || d[0].toInt() != 1) return
            val bb = ByteBuffer.wrap(d)
            bb.get()
            val tx = bb.int.toLong() and 0xffffffffL
            val packets = ArrayList<ByteArray>(3)
            while (bb.remaining() >= 2) {
                val len = bb.short.toInt() and 0xffff
                if (len == 0 || len > bb.remaining()) break
                val p = ByteArray(len)
                bb.get(p)
                packets.add(p)
            }
            onAudio?.invoke(tx, packets)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, response?.code)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    private fun dropped(socket: WebSocket, httpCode: Int?) {
        main.post {
            if (socket !== ws) return@post
            val was = isConnected
            isConnected = false
            ws = null
            main.removeCallbacks(pinger)
            if (was) onEvent?.invoke(RelayEvent.Disconnected)
            if (!wanted || httpCode == 401 || httpCode == 403) {
                if (httpCode == 401 || httpCode == 403) onEvent?.invoke(RelayEvent.Disconnected)
                return@post
            }
            val delay = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            main.postDelayed({ if (wanted && ws == null) open() }, delay)
        }
    }
}
