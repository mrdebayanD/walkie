package com.debayandatta.walkie.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.debayandatta.walkie.audio.Audio
import com.debayandatta.walkie.net.Api
import com.debayandatta.walkie.net.ApiException
import com.debayandatta.walkie.net.Channel
import com.debayandatta.walkie.net.Clip
import com.debayandatta.walkie.net.Member
import com.debayandatta.walkie.net.Relay
import com.debayandatta.walkie.net.RelayEvent
import com.debayandatta.walkie.net.Settings
import com.debayandatta.walkie.net.SosEvent
import com.debayandatta.walkie.net.TalkMode
import com.debayandatta.walkie.service.DutyService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { LOADING, NEEDS_JOIN, PENDING, READY }

sealed class Talk {
    open val mode: TalkMode? get() = null
    val active get() = this != Idle

    data object Idle : Talk()
    data class Requesting(override val mode: TalkMode) : Talk()
    data class Talking(override val mode: TalkMode, val since: Long) : Talk()
}

data class Remote(val channel: String, val txId: Long, val userId: String, val name: String, val since: Long)

data class UiState(
    val phase: Phase = Phase.LOADING,
    val me: Member? = null,
    val server: String? = null,
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: String? = null,
    val settings: Settings = Settings(24),
    val clips: Map<String, List<Clip>> = emptyMap(),
    val played: Set<String> = emptySet(),
    val pending: List<Member> = emptyList(),
    val tab: Int = 0,
    /** Group channel to go back to after talking to one person. */
    val returnChannelId: String? = null,
    val connected: Boolean = false,
    val talk: Talk = Talk.Idle,
    val remote: Remote? = null,
    val level: Float = 0f,
    val playingClipId: String? = null,
    val playProgress: Float = 0f,
    val onDuty: Boolean = true,
    val lowPower: Boolean = false,
    val muted: Boolean = false,
    val incomingSos: SosEvent? = null,
    val mySos: SosEvent? = null,
    val sosHistory: List<SosEvent> = emptyList(),
    val error: String? = null,
) {
    val selectedChannel: Channel? get() = channels.firstOrNull { it.id == selectedChannelId } ?: channels.firstOrNull()
    val defaultChannel: Channel? get() = channels.firstOrNull { it.isDefault }
    val groupChannels: List<Channel> get() = channels.filter { !it.isPersonal }
    val personalChannels: List<Channel> get() = channels.filter { it.isPersonal }.sortedBy { it.title(me?.id) }
    val returnChannel: Channel? get() = channels.firstOrNull { it.id == returnChannelId } ?: defaultChannel
    fun title(c: Channel) = c.title(me?.id)
    val everyone: List<Member> get() = channels.flatMap { it.members }.distinctBy { it.id }.sortedBy { it.name }
    fun unheard(channelId: String) = (clips[channelId] ?: emptyList()).count { it.id !in played && it.userId != me?.id }
    val isAdmin get() = me?.isAdmin == true
    fun member(id: String): Member? = channels.asSequence().flatMap { it.members }.firstOrNull { it.id == id }
    fun nameOf(id: String) = member(id)?.name ?: "Someone"
}

/** The app's brain: one instance per process, shared by the UI and services. */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Walkie {
    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set
    lateinit var audio: Audio
        private set
    private lateinit var locator: Locator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val s get() = _state.value

    var api: Api? = null
        private set
    private var relay: Relay? = null
    private var appInForeground = false
    private var grantedTx: Long? = null
    private val preGrant = ArrayList<List<ByteArray>>()
    private var talkTimeout: Runnable? = null
    private var pendingPoll: Job? = null
    private val clipCache = HashMap<String, ByteArray>()
    private var playJob: Job? = null
    @Volatile private var wakeAfterStart = false

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        audio = Audio(app)
        locator = Locator(app)
        audio.speakerMuted = prefs.speakerMuted
        audio.onPackets = { batch -> main.post { outgoing(batch) } }
        audio.onLevel = { l -> _state.update { it.copy(level = l) } }
        _state.update { it.copy(onDuty = prefs.onDuty, lowPower = prefs.lowPower, muted = prefs.speakerMuted, played = prefs.played) }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { appInForeground = true; updateConnection(); refreshSoon() }
            override fun onStop(owner: LifecycleOwner) { appInForeground = false; updateConnection() }
        })
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { relay?.nudge() }
        })
        scope.launch { start() }
    }

    private fun setError(msg: String?) = _state.update { it.copy(error = msg) }
    fun clearError() = setError(null)

    // ---------------------------------------------------------------- session

    private suspend fun start() {
        val server = prefs.server
        val token = prefs.token
        if (server == null || token == null) {
            _state.update { it.copy(phase = Phase.NEEDS_JOIN) }
            return
        }
        api = Api(server, token)
        _state.update { it.copy(server = server) }
        refreshMe()
    }

    private fun refreshSoon() { if (s.phase == Phase.READY) scope.launch { loadChannels(); loadSos() } }

    suspend fun refreshMe() {
        val api = api ?: return
        try {
            val (me, settings) = api.me()
            _state.update { it.copy(me = me, settings = settings, error = null) }
            if (me.isPending) {
                _state.update { it.copy(phase = Phase.PENDING) }
                startPendingPoll()
            } else {
                pendingPoll?.cancel()
                if (s.phase != Phase.READY) {
                    _state.update { it.copy(phase = Phase.READY) }
                    becameReady()
                }
            }
        } catch (e: ApiException) {
            if (e.status == 401) signOut() else retryMe(e.message)
        } catch (e: Exception) {
            retryMe("Can't reach the server. Retrying…")
        }
    }

    private fun retryMe(msg: String?) {
        setError(msg)
        scope.launch { delay(5000); refreshMe() }
    }

    private fun startPendingPoll() {
        pendingPoll?.cancel()
        pendingPoll = scope.launch {
            while (true) {
                delay(8000)
                if (s.phase != Phase.PENDING) return@launch
                refreshMe()
            }
        }
    }

    private suspend fun becameReady() {
        val server = prefs.server ?: return
        val token = prefs.token ?: return
        loadChannels()
        loadSos()
        if (s.isAdmin) loadPending()
        val r = Relay(server, token)
        r.duty = s.onDuty
        r.onEvent = { handle(it) }
        r.onAudio = { tx, packets -> if (s.remote?.txId == tx) audio.play(packets) }
        relay = r
        updateConnection()
        registerPush()
        if (wakeAfterStart) { wakeAfterStart = false; wakeForTalk() }
    }

    /** Parses https://…/join?c=CODE or walkie://join?s=…&c=…, or server + code typed by hand. */
    fun parseInvite(link: String, code: String?): Pair<String, String>? {
        val u = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        if (u.scheme == "walkie") {
            val server = u.getQueryParameter("s") ?: return null
            val c = u.getQueryParameter("c") ?: return null
            return server.trimEnd('/') to c
        }
        if ((u.scheme == "https" || u.scheme == "http") && u.host != null) {
            val base = "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
            val c = u.getQueryParameter("c")?.takeIf { it.isNotBlank() } ?: code?.takeIf { it.isNotBlank() } ?: return null
            return base to c
        }
        return null
    }

    suspend fun join(link: String, code: String?, name: String, phone: String = "") {
        setError(null)
        val (server, c) = parseInvite(link, code) ?: run {
            setError("That doesn't look like an invite link. Paste the whole link, or the server address and code.")
            return
        }
        try {
            val a = Api(server, null)
            val (token, _) = a.join(c, name, phone)
            prefs.server = server
            prefs.token = token
            a.token = token
            api = a
            _state.update { it.copy(phase = Phase.LOADING, server = server) }
            refreshMe()
        } catch (e: Exception) {
            setError(e.message ?: "Couldn't join")
        }
    }

    fun signOut() {
        relay?.disconnect()
        relay = null
        DutyService.stop(app)
        prefs.clearSession()
        api = null
        _state.update { UiState(phase = Phase.NEEDS_JOIN, onDuty = prefs.onDuty, lowPower = prefs.lowPower, muted = prefs.speakerMuted) }
    }

    // ---------------------------------------------------------------- connection policy

    /**
     * On duty (normal): a foreground service keeps one quiet connection open.
     * Low-power: connected only while the app is open; pushes wake it.
     * Off duty: connected only while the app is open, hearing nothing live.
     */
    fun updateConnection() {
        val r = relay ?: return
        val keepAlive = s.onDuty && !s.lowPower
        // Services may only be started while the app is visible (or from a
        // high-priority push, see wakeForTalk). Started with the mic type while
        // visible, the service keeps mic access after the screen locks.
        if (keepAlive && appInForeground) DutyService.start(app, withMic = hasMic())
        if (appInForeground || keepAlive || s.talk.active || s.remote != null) r.connect() else {
            r.disconnect()
            if (!keepAlive) DutyService.stop(app)
        }
    }

    /** A push says someone is talking: connect (the server replays the start). */
    fun wakeForTalk() {
        if (!prefs.onDuty) return
        if (s.phase != Phase.READY) {
            // Process was just started by the push: wake once the session is ready.
            wakeAfterStart = true
            DutyService.start(app, withMic = false) // must start within the push's short window
            return
        }
        if (relay?.isConnected == true) {
            // Probably already playing. If Doze froze the socket, nothing has
            // arrived yet: reconnect so the server replays the transmission.
            main.postDelayed({ if (s.remote == null && !s.talk.active) relay?.reconnectNow() }, 2000)
            return
        }
        DutyService.start(app, withMic = false)
        relay?.connect()
        // Drop the connection again after the conversation if we're in low-power mode.
        main.postDelayed({ if (s.lowPower && !appInForeground && s.remote == null && !s.talk.active) relay?.disconnect() }, 45_000)
    }

    fun setOnDuty(on: Boolean) {
        prefs.onDuty = on
        _state.update { st ->
            val myId = st.me?.id
            st.copy(onDuty = on, channels = st.channels.map { c -> c.copy(members = c.members.map { m -> if (m.id == myId) m.copy(duty = on) else m }) })
        }
        relay?.updateDuty(on)
        if (!on) DutyService.stop(app)
        updateConnection()
    }

    fun setLowPower(on: Boolean) {
        prefs.lowPower = on
        _state.update { it.copy(lowPower = on) }
        if (on && !appInForeground) DutyService.stop(app)
        updateConnection()
    }

    fun setMuted(on: Boolean) {
        prefs.speakerMuted = on
        audio.speakerMuted = on
        _state.update { it.copy(muted = on) }
    }

    fun select(channelId: String) {
        prefs.selectedChannel = channelId
        _state.update { it.copy(selectedChannelId = channelId) }
        scope.launch { loadClips(channelId) }
    }

    fun setTab(t: Int) = _state.update { it.copy(tab = t) }

    /** Tap a person: talk to them only (their personal channel), on the Talk tab. */
    fun talkPrivately(m: Member) {
        scope.launch {
            val api = api ?: return@launch
            try {
                val c = api.personalChannel(m.id)
                val back = s.selectedChannel?.takeIf { !it.isPersonal }?.id
                if (back != null) _state.update { it.copy(returnChannelId = back) }
                loadChannels()
                select(c.id)
                setTab(0)
            } catch (e: Exception) { setError(e.message) }
        }
    }

    fun backToGroup() {
        s.returnChannel?.let { select(it.id) }
        _state.update { it.copy(returnChannelId = null) }
    }

    fun deletePersonal(c: Channel) {
        scope.launch {
            runCatching { api?.deleteChannel(c.id) }
            if (s.selectedChannelId == c.id) s.defaultChannel?.let { select(it.id) }
            loadChannels()
        }
    }

    suspend fun loadAllClips() { s.channels.forEach { loadClips(it.id) } }

    fun hasMic() = ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- loading

    suspend fun loadChannels() {
        val api = api ?: return
        val cs = runCatching { api.channels() }.getOrNull() ?: return
        _state.update { st ->
            val sel = st.selectedChannelId?.takeIf { id -> cs.any { it.id == id } }
                ?: prefs.selectedChannel?.takeIf { id -> cs.any { it.id == id } }
                ?: cs.firstOrNull()?.id
            st.copy(channels = cs, selectedChannelId = sel)
        }
        s.selectedChannelId?.let { loadClips(it) }
    }

    suspend fun loadPending() {
        val api = api ?: return
        if (!s.isAdmin) return
        runCatching { api.users() }.getOrNull()?.let { us -> _state.update { it.copy(pending = us.filter { u -> u.isPending }) } }
    }

    suspend fun loadClips(channelId: String) {
        val api = api ?: return
        runCatching { api.clips(channelId) }.getOrNull()?.let { list -> _state.update { it.copy(clips = it.clips + (channelId to list)) } }
    }

    suspend fun loadSos() {
        val api = api ?: return
        val list = runCatching { api.sosList() }.getOrNull() ?: return
        val me = s.me?.id
        _state.update { it.copy(sosHistory = list, mySos = list.firstOrNull { e -> e.isActive && e.userId == me }) }
        if (s.incomingSos == null) list.firstOrNull { it.isActive && it.userId != me }?.let { showIncoming(it) }
    }

    // ---------------------------------------------------------------- relay events

    private fun handle(e: RelayEvent) {
        when (e) {
            RelayEvent.Connected -> {
                _state.update { it.copy(connected = true, error = null) }
                scope.launch { loadChannels(); loadSos() }
            }
            RelayEvent.Disconnected -> {
                _state.update { it.copy(connected = false) }
                if (s.talk.active) stopLocalTalk()
                if (s.remote != null) remoteEnded()
                if (prefs.token == null) return
                // 401 after removal shows up here as a drop; check who we are.
                scope.launch { delay(1500); if (!s.connected) refreshMeQuietly() }
            }
            is RelayEvent.FloorGranted -> {
                val t = s.talk
                if (t !is Talk.Requesting) { relay?.releaseFloor(); return }
                grantedTx = e.txId
                _state.update { it.copy(talk = Talk.Talking(t.mode, System.currentTimeMillis())) }
                preGrant.forEach { relay?.sendAudio(it) }
                preGrant.clear()
                buzz(30)
                talkTimeout?.let { main.removeCallbacks(it) }
                talkTimeout = Runnable { endTalk() }.also { main.postDelayed(it, t.mode.limitMs) }
            }
            is RelayEvent.FloorDenied -> {
                stopLocalTalk()
                buzz(200)
                setError(if (e.reason == "busy") "${s.remote?.name ?: "Someone"} is talking. Wait for them to finish." else "You can't talk on this channel.")
            }
            is RelayEvent.FloorTaken -> {
                if (e.userId == s.me?.id) return
                // Already hearing someone on another channel: this one goes to History (unheard).
                if (s.remote != null) return
                audio.newStream()
                _state.update { it.copy(remote = Remote(e.channel, e.txId, e.userId, e.name, System.currentTimeMillis())) }
                if (s.muted) buzz(60)
            }
            is RelayEvent.FloorEnded -> {
                if (e.userId == s.me?.id) {
                    if (grantedTx == e.txId) stopLocalTalk()
                } else if (s.remote?.txId == e.txId) {
                    remoteEnded()
                    if (e.clipId != null && !s.muted) markPlayed(e.clipId)
                }
                scope.launch { loadClips(e.channel) }
            }
            is RelayEvent.Presence -> _state.update { st ->
                st.copy(channels = st.channels.map { c -> c.copy(members = c.members.map { m -> if (m.id == e.userId) m.copy(online = e.online, duty = e.duty) else m }) })
            }
            is RelayEvent.Sos -> handleSos(e.sos)
            RelayEvent.ChannelsChanged -> scope.launch { loadChannels(); loadPending() }
            RelayEvent.UsersChanged -> scope.launch { loadPending() }
            is RelayEvent.SettingsChanged -> _state.update { it.copy(settings = e.settings) }
        }
    }

    private suspend fun refreshMeQuietly() {
        val api = api ?: return
        try { api.me() } catch (e: ApiException) { if (e.status == 401) signOut() } catch (_: Exception) {}
    }

    private fun remoteEnded() {
        _state.update { it.copy(remote = null) }
        updateConnection()
    }

    // ---------------------------------------------------------------- talking

    fun beginTalk(mode: TalkMode, sosId: String? = null): Boolean {
        if (s.talk.active) return false
        if (s.remote != null && mode != TalkMode.SOS) {
            buzz(200); setError("${s.remote?.name} is talking. Wait for them to finish."); return false
        }
        if (!hasMic()) { setError("Allow the microphone to talk."); return false }
        val channel = if (mode == TalkMode.SOS) s.mySos?.channelId else s.selectedChannel?.id
        if (channel == null) return false
        if (relay?.isConnected != true) {
            relay?.connect()
            buzz(200); setError("Not connected to the server yet."); return false
        }
        // Keep the mic allowed if the screen locks during hands-free.
        if (appInForeground) DutyService.start(app, withMic = true)
        _state.update { it.copy(talk = Talk.Requesting(mode)) }
        preGrant.clear()
        audio.startCapture()
        relay?.requestFloor(channel, mode, sosId)
        return true
    }

    fun endTalk() {
        if (!s.talk.active) return
        relay?.releaseFloor()
        stopLocalTalk()
        buzz(20)
    }

    private fun stopLocalTalk() {
        talkTimeout?.let { main.removeCallbacks(it) }
        audio.stopCapture()
        grantedTx = null
        preGrant.clear()
        _state.update { it.copy(talk = Talk.Idle, level = 0f) }
        updateConnection()
    }

    private fun outgoing(batch: List<ByteArray>) {
        when (s.talk) {
            is Talk.Talking -> relay?.sendAudio(batch)
            is Talk.Requesting -> if (preGrant.size < 20) preGrant.add(batch) // ~1.2 s pre-roll
            Talk.Idle -> {}
        }
    }

    // ---------------------------------------------------------------- history

    fun markPlayed(id: String) {
        val p = s.played + id
        prefs.played = p
        _state.update { it.copy(played = p) }
    }

    fun play(clip: Clip) {
        if (s.playingClipId == clip.id) {
            playJob?.cancel(); audio.stopPlayback(); _state.update { it.copy(playingClipId = null) }; return
        }
        playJob?.cancel()
        playJob = scope.launch {
            val api = api ?: return@launch
            try {
                val data = clipCache[clip.id] ?: api.clipAudio(clip.id).also { clipCache[clip.id] = it }
                markPlayed(clip.id)
                _state.update { it.copy(playingClipId = clip.id, playProgress = 0f) }
                withContext(Dispatchers.Default) {
                    audio.playClipBlocking(data) { p -> _state.update { it.copy(playProgress = p) } }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) setError("Couldn't load that clip.")
            } finally {
                if (s.playingClipId == clip.id) _state.update { it.copy(playingClipId = null) }
            }
        }
    }

    fun playMissed(channelId: String) {
        scope.launch {
            val missed = (s.clips[channelId] ?: emptyList()).filter { it.id !in s.played && it.userId != s.me?.id }.reversed()
            for (c in missed) {
                play(c)
                playJob?.join()
            }
        }
    }

    // ---------------------------------------------------------------- SOS

    fun sendSos() {
        scope.launch {
            val api = api ?: return@launch
            val loc = locator.current()
            try {
                val sos = api.sendSos(loc?.latitude, loc?.longitude, loc?.accuracy?.toDouble())
                _state.update { it.copy(mySos = sos) }
                buzz(400)
                beginTalk(TalkMode.SOS, sos.id) // 15-second clip for the family
                locator.startTracking { l ->
                    val cur = s.mySos ?: return@startTracking
                    scope.launch { runCatching { api.sosLocation(cur.id, l.latitude, l.longitude, l.accuracy.toDouble()) } }
                }
            } catch (e: Exception) {
                setError("SOS failed to send: ${e.message}. Call 112.")
            }
        }
    }

    fun resolveMySos(falseAlarm: Boolean) {
        val sos = s.mySos ?: return
        scope.launch {
            runCatching { api?.sosResolve(sos.id, falseAlarm) }
            locator.stopTracking()
            _state.update { it.copy(mySos = null) }
            loadSos()
        }
    }

    fun respond(sosId: String) { scope.launch { runCatching { api?.sosRespond(sosId) } } }

    fun dismissIncoming() = _state.update { it.copy(incomingSos = null) }

    /** From a notification tap: load and show this SOS. */
    fun openSos(sosId: String) {
        scope.launch {
            loadSos()
            s.sosHistory.firstOrNull { it.id == sosId }?.let { showIncoming(it) }
        }
    }

    private fun showIncoming(sos: SosEvent) {
        _state.update { it.copy(incomingSos = sos) }
        buzz(600)
        scope.launch { runCatching { api?.sosAck(sos.id) } }
    }

    private fun handleSos(sos: SosEvent) {
        val me = s.me?.id
        if (sos.userId == me) {
            _state.update { it.copy(mySos = if (sos.isActive) sos else null) }
            if (!sos.isActive) locator.stopTracking()
        } else if (sos.isActive) {
            if (s.incomingSos?.id == sos.id) _state.update { it.copy(incomingSos = sos) }
            else if (s.incomingSos == null) {
                showIncoming(sos)
                if (!appInForeground) Notifications.sos(app, sos.id, sos.userName)
            }
        } else {
            if (s.incomingSos?.id == sos.id) _state.update { it.copy(incomingSos = sos) }
            Notifications.sosResolved(app, sos.id, sos.userName, sos.resolution == "false_alarm")
        }
        _state.update { st ->
            val h = st.sosHistory.toMutableList()
            val i = h.indexOfFirst { it.id == sos.id }
            if (i >= 0) h[i] = sos else h.add(0, sos)
            st.copy(sosHistory = h)
        }
    }

    // ---------------------------------------------------------------- push

    private fun registerPush() {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> onNewPushToken(t) }
        } // fails harmlessly when google-services.json was not added
    }

    fun onNewPushToken(token: String) {
        prefs.fcmToken = token
        scope.launch { runCatching { api?.setFcmToken(token) } }
    }

    // ---------------------------------------------------------------- misc

    fun buzz(ms: Long) {
        val v = app.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun updateSettingsLocal(settings: Settings) = _state.update { it.copy(settings = settings) }
    fun updateMeLocal(me: Member) = _state.update { it.copy(me = me) }
}
