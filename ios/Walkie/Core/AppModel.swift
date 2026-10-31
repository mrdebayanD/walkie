import AVFoundation
import Combine
import Foundation
import UIKit
import UserNotifications

enum Phase: Equatable { case loading, needsJoin, pending, ready }

enum TalkState: Equatable {
    case idle
    case requesting(TalkMode)
    case talking(TalkMode, since: Date)

    var isActive: Bool { self != .idle }
    var mode: TalkMode? {
        switch self {
        case .idle: return nil
        case .requesting(let m), .talking(let m, _): return m
        }
    }
}

struct RemoteTalk: Equatable {
    let channel: String
    let txId: UInt32
    let userId: String
    let name: String
    let since: Date
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    // Session
    @Published var phase: Phase = .loading
    @Published var me: Member?
    @Published var serverURL: URL?
    @Published var errorMessage: String?

    // Data
    @Published var channels: [Channel] = []
    @Published var selectedChannelId: String? { didSet { channelSelectionChanged(oldValue) } }
    @Published var selectedTab = 0
    /// The group channel to return to after talking to one person.
    @Published var returnChannelId: String?
    var returnChannel: Channel? { channels.first { $0.id == returnChannelId } ?? defaultChannel }

    func backToGroup() {
        selectedChannelId = returnChannel?.id
        returnChannelId = nil
    }
    @Published var settings = Settings(clipTtlHours: 24)
    @Published var clips: [String: [Clip]] = [:]
    @Published var playedClips: Set<String> = []
    @Published var pendingUsers: [Member] = []
    @Published var connected = false

    // Talk
    @Published var talk: TalkState = .idle
    @Published var remote: RemoteTalk?
    @Published var level: Float = 0
    @Published var lastMessage: (name: String, at: Date, clipId: String?, durationMs: Int)?
    @Published var playingClipId: String?
    @Published var playProgress: Double = 0

    // Settings on this phone
    @Published var onDuty: Bool = UserDefaults.standard.object(forKey: "onDuty") as? Bool ?? true {
        didSet { UserDefaults.standard.set(onDuty, forKey: "onDuty"); dutyChanged() }
    }
    @Published var speakerMuted: Bool = UserDefaults.standard.bool(forKey: "speakerMuted") {
        didSet { UserDefaults.standard.set(speakerMuted, forKey: "speakerMuted"); audio.speakerMuted = speakerMuted }
    }

    // SOS
    @Published var incomingSOS: SOSEvent?
    @Published var mySOS: SOSEvent?
    @Published var sosHistory: [SOSEvent] = []

    let audio = AudioEngine()
    let ptt = PTTController()
    private(set) var api: API?
    private var relay: Relay?
    private var talkTimer: Timer?
    private var grantedTx: UInt32?
    /// Outgoing audio goes straight from the audio thread to the socket, in order.
    private let gate = TxGate()
    private var sessionActive = false
    private var heldRemote: [Data] = []
    private var pendingSOSClip: String?
    private var pollTask: Task<Void, Never>?
    private var clipCache: [String: Data] = [:]
    private var alertToken: String?
    private var sosLocationTimer: Timer?

    private init() {
        audio.speakerMuted = speakerMuted
        playedClips = Set(UserDefaults.standard.stringArray(forKey: "playedClips") ?? [])
        let gate = self.gate
        audio.onPackets = { packets in gate.push(packets) }
        audio.onLevel = { [weak self] l in
            Task { @MainActor in self?.level = l }
        }
        ptt.delegate = self
    }

    var isAdmin: Bool { me?.isAdmin == true }
    var selectedChannel: Channel? { channels.first { $0.id == selectedChannelId } ?? channels.first }
    var defaultChannel: Channel? { channels.first { $0.isDefault } }
    var groupChannels: [Channel] { channels.filter { !$0.isPersonal } }
    var personalChannels: [Channel] { channels.filter { $0.isPersonal }.sorted { title($0) < title($1) } }
    func title(_ c: Channel) -> String { c.title(me: me?.id) }

    /// Everyone you share a channel with, plus you.
    var everyone: [Member] {
        var seen = Set<String>(), out: [Member] = []
        for c in channels { for m in c.members where !seen.contains(m.id) { seen.insert(m.id); out.append(m) } }
        return out.sorted { $0.name < $1.name }
    }

    func unheard(in channelId: String) -> Int {
        (clips[channelId] ?? []).filter { !playedClips.contains($0.id) && $0.userId != me?.id }.count
    }

    func loadAllClips() async {
        for c in channels { await loadClips(c.id) }
    }

    /// Opens the personal channel with someone and switches to the Talk tab.
    func talkPrivately(with m: Member) async {
        guard let api else { return }
        do {
            let c = try await api.personalChannel(with: m.id)
            if selectedChannel?.isPersonal != true { returnChannelId = selectedChannel?.id }
            await loadChannels()
            selectedChannelId = c.id
            selectedTab = 0
        } catch { errorMessage = error.localizedDescription }
    }

    func deletePersonal(_ c: Channel) async {
        try? await api?.deleteChannel(c.id)
        if selectedChannelId == c.id { selectedChannelId = defaultChannel?.id }
        await loadChannels()
    }

    // MARK: launch

    func start() async {
        AudioEngine.configureSession()
        await ptt.setUp()
        guard let s = Keychain.get("server"), let url = URL(string: s), let token = Keychain.get("token") else {
            phase = .needsJoin
            return
        }
        serverURL = url
        api = API(baseURL: url, token: token)
        // Joined before: open straight away from the saved copy and reconnect in
        // the background, so a server that's down never blocks the app.
        if let cachedMe = LocalCache.load(Member.self, "me"), !cachedMe.isPending {
            me = cachedMe
            settings = LocalCache.load(Settings.self, "settings") ?? settings
            channels = LocalCache.load([Channel].self, "channels") ?? []
            clips = LocalCache.load([String: [Clip]].self, "clips") ?? [:]
            let saved = UserDefaults.standard.string(forKey: "selectedChannel")
            selectedChannelId = channels.first { $0.id == saved }?.id ?? channels.first?.id
            phase = .ready
            await becameReady()
            await refreshMe()
            return
        }
        await refreshMe()
    }

    func refreshMe() async {
        guard let api else { return }
        do {
            let r = try await api.me()
            me = r.user
            settings = r.settings
            LocalCache.save(r.user, "me")
            LocalCache.save(r.settings, "settings")
            if r.user.isPending {
                phase = .pending
                startPendingPoll()
            } else {
                pollTask?.cancel()
                if phase != .ready {
                    phase = .ready
                    await becameReady()
                }
            }
        } catch let e as APIError where e.status == 401 {
            signOut() // removed by the admin
        } catch {
            // Offline. On the loading screen, show it and retry; once running,
            // the relay's own reconnect handles it quietly.
            if phase == .loading || phase == .pending {
                errorMessage = "Can't reach the server. Retrying…"
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                    Task { await self?.refreshMe() }
                }
            }
        }
    }

    private func startPendingPoll() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                guard let self, self.phase == .pending else { return }
                await self.refreshMe()
            }
        }
    }

    private func becameReady() async {
        guard let url = serverURL, let token = api?.token else { return }
        // Connect first: if the server is down, the loads below wait for timeouts.
        let r = Relay(serverURL: url, token: token)
        r.duty = onDuty
        r.onEvent = { [weak self] e in self?.handle(e) }
        relay = r
        gate.relay = r
        r.connect()
        if onDuty, let ch = selectedChannel { ptt.join(channelId: ch.id, name: title(ch)) }
        await loadChannels()
        await loadSOS()
        if isAdmin { await loadPending() }
        await registerNotifications()
    }

    /// Accepts a full invite link (https://…/join?c=CODE or walkie://join?s=…&c=…)
    /// or a server address plus a code typed by hand.
    func join(link: String, code: String?, name: String, phone: String = "") async {
        errorMessage = nil
        guard let (server, c) = AppModel.parseInvite(link: link, code: code) else {
            errorMessage = "That doesn't look like an invite link. Paste the whole link, or the server address and code."
            return
        }
        let api = API(baseURL: server, token: nil)
        do {
            let r = try await api.join(code: c, name: name, phone: phone)
            Keychain.set(server.absoluteString, for: "server")
            Keychain.set(r.token, for: "token")
            serverURL = server
            api.token = r.token
            self.api = api
            phase = .loading
            await refreshMe()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func parseInvite(link: String, code: String?) -> (URL, String)? {
        let t = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let comps = URLComponents(string: t) else { return nil }
        let q = Dictionary(uniqueKeysWithValues: (comps.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        if comps.scheme == "walkie", let s = q["s"], let c = q["c"], let u = URL(string: s) { return (u, c) }
        if let scheme = comps.scheme, scheme == "https" || scheme == "http", let host = comps.host {
            var base = URLComponents()
            base.scheme = scheme
            base.host = host
            base.port = comps.port
            guard let u = base.url else { return nil }
            if let c = q["c"], !c.isEmpty { return (u, c) }
            if let code, !code.isEmpty { return (u, code) }
        }
        return nil
    }

    func signOut() {
        relay?.disconnect()
        relay = nil
        ptt.leave()
        Keychain.set(nil, for: "token")
        Keychain.set(nil, for: "server")
        LocalCache.clear()
        api = nil
        me = nil
        channels = []
        phase = .needsJoin
    }

    // MARK: data loading

    func loadChannels() async {
        guard let api else { return }
        if let cs = try? await api.channels() {
            channels = cs
            LocalCache.save(cs, "channels")
            if selectedChannelId == nil || !cs.contains(where: { $0.id == selectedChannelId }) {
                selectedChannelId = (UserDefaults.standard.string(forKey: "selectedChannel")).flatMap { id in cs.first { $0.id == id }?.id } ?? cs.first?.id
            }
        }
    }

    func loadPending() async {
        guard let api, isAdmin else { return }
        if let us = try? await api.users() { pendingUsers = us.filter { $0.isPending } }
    }

    func loadClips(_ channelId: String) async {
        guard let api else { return }
        if let cs = try? await api.clips(channel: channelId) {
            clips[channelId] = cs
            LocalCache.save(clips, "clips")
            if let first = cs.first(where: { $0.mode != "sos" }) {
                lastMessage = (first.userName, first.startedAt, first.id, first.durationMs)
            }
        }
    }

    func loadSOS() async {
        guard let api else { return }
        guard let list = try? await api.sosList() else { return }
        sosHistory = list
        mySOS = list.first { $0.isActive && $0.userId == me?.id }
        if incomingSOS == nil, let s = list.first(where: { $0.isActive && $0.userId != me?.id }) {
            showIncoming(s)
        }
    }

    private func channelSelectionChanged(_ old: String?) {
        guard selectedChannelId != old, let ch = selectedChannel else { return }
        UserDefaults.standard.set(ch.id, forKey: "selectedChannel")
        if onDuty { ptt.join(channelId: ch.id, name: title(ch)) }
        Task { await loadClips(ch.id) }
    }

    private func dutyChanged() {
        relay?.setDuty(onDuty)
        if let myId = me?.id { // show my own change right away everywhere (avatars, counts)
            for i in channels.indices {
                for j in channels[i].members.indices where channels[i].members[j].id == myId {
                    channels[i].members[j].duty = onDuty
                }
            }
        }
        if onDuty, let ch = selectedChannel { ptt.join(channelId: ch.id, name: title(ch)) } else { ptt.leave() }
    }

    // MARK: relay events

    private func handle(_ e: RelayEvent) {
        switch e {
        case .connected:
            connected = true
            errorMessage = nil
            ptt.setConnected(true)
            Task { await loadChannels(); await loadSOS() }
        case .disconnected:
            connected = false
            ptt.setConnected(false)
            if talk.isActive { endTalk() }
            if remote != nil { remoteEnded() }
            // If the admin removed this phone, the server now refuses us: find out and sign out.
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if !connected { await refreshMe() }
            }
        case .welcome:
            break
        case .floorGranted(_, let tx, _):
            guard case .requesting(let mode) = talk else {
                relay?.releaseFloor() // we already let go
                return
            }
            grantedTx = tx
            talk = .talking(mode, since: Date())
            gate.grant()
            Haptics.start()
            talkTimer?.invalidate()
            talkTimer = Timer.scheduledTimer(withTimeInterval: mode.limit, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.endTalk() }
            }
        case .floorDenied(_, let reason):
            stopLocalTalk()
            Haptics.error()
            errorMessage = reason == "busy" ? "\(remote?.name ?? "Someone") is talking. Wait for them to finish." : "You can't talk on this channel."
        case .floorTaken(let ch, let tx, let uid, let name, _):
            guard uid != me?.id else { return }
            // Already hearing someone on another channel: this one goes to History (unheard).
            guard remote == nil else { return }
            remote = RemoteTalk(channel: ch, txId: tx, userId: uid, name: name, since: Date())
            heldRemote.removeAll()
            if speakerMuted { Haptics.incoming() }
            let chan = channels.first { $0.id == ch }
            let label = chan?.isPersonal == true ? "\(name) (personal)" : (chan.map { "\(name) · \(title($0))" } ?? name)
            if ptt.isJoined {
                ptt.setRemoteSpeaker(label)
            } else {
                AudioEngine.activateForPlayback()
                sessionActive = true
                audio.sessionActivated()
            }
        case .floorEnded(let ch, let tx, let uid, let clipId, let reason):
            let heardLive = remote?.txId == tx
            if uid == me?.id {
                if grantedTx == tx { stopLocalTalk() }
                if reason == "timeout" { Haptics.stop() }
            } else if heardLive {
                remoteEnded()
            }
            Task { await loadClips(ch) }
            // Heard live = played (only if it actually played out loud).
            if let clipId, uid != me?.id, heardLive, !speakerMuted { markPlayed(clipId) }
        case .audio(let tx, let packets):
            guard remote?.txId == tx else { return }
            if sessionActive { audio.play(packets: packets) } else { heldRemote.append(contentsOf: packets) }
        case .presence(let uid, let online, let duty):
            for i in channels.indices {
                for j in channels[i].members.indices where channels[i].members[j].id == uid {
                    channels[i].members[j].online = online
                    channels[i].members[j].duty = duty
                }
            }
        case .sos(let s):
            handleSOSUpdate(s)
        case .channelsChanged:
            Task { await loadChannels(); if isAdmin { await loadPending() } }
        case .usersChanged:
            Task { await loadPending() }
        case .settingsChanged(let s):
            settings = s
        }
    }

    private func remoteEnded() {
        audio.endOfStream()
        remote = nil
        heldRemote.removeAll()
        // Give the last buffers time to play before the system closes audio.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self, self.remote == nil else { return }
            if self.ptt.isJoined { self.ptt.setRemoteSpeaker(nil) }
        }
    }

    // MARK: talking

    /// Press-and-hold or double-tap from the talk button.
    func beginTalk(_ mode: TalkMode, sosId: String? = nil) {
        guard talk == .idle else { return }
        if remote != nil && mode != .sos {
            Haptics.error()
            errorMessage = "\(remote!.name) is talking. Wait for them to finish."
            return
        }
        guard connected else {
            Haptics.error()
            errorMessage = "Not connected to the server yet."
            return
        }
        guard let ch = (mode == .sos ? mySOS?.channelId : selectedChannel?.id) else { return }
        switch AVAudioSession.sharedInstance().recordPermission {
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { _ in }
            return
        case .denied:
            errorMessage = "Microphone access is off. Turn it on in Settings › Hollo."
            return
        default: break
        }
        talk = .requesting(mode)
        pendingSOSClip = sosId
        gate.request()
        if ptt.isJoined {
            ptt.beginTransmitting() // the system then calls pttDidBeginTransmitting + pttAudioActivated
        } else {
            AudioEngine.activateForPlayback()
            sessionActive = true
            audio.sessionActivated()
            audio.startCapture()
        }
        relay?.requestFloor(channel: ch, mode: mode, sosId: sosId)
    }

    func endTalk() {
        guard talk.isActive else { return }
        relay?.releaseFloor()
        stopLocalTalk()
        Haptics.stop()
    }

    private func stopLocalTalk() {
        talkTimer?.invalidate()
        audio.stopCapture()
        if ptt.isJoined { ptt.stopTransmitting() }
        talk = .idle
        grantedTx = nil
        gate.reset()
        level = 0
    }

    // MARK: history

    func markPlayed(_ id: String) {
        playedClips.insert(id)
        UserDefaults.standard.set(Array(playedClips.suffix(2000)), forKey: "playedClips")
    }

    func play(_ clip: Clip) async {
        guard let api else { return }
        if playingClipId == clip.id {
            audio.stopPlayback()
            playingClipId = nil
            return
        }
        do {
            let data: Data
            if let cached = clipCache[clip.id] { data = cached } else {
                data = try await api.clipAudio(clip.id)
                clipCache[clip.id] = data
            }
            if !sessionActive { AudioEngine.activateForPlayback() }
            audio.sessionActivated()
            playingClipId = clip.id
            playProgress = 0
            markPlayed(clip.id)
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                audio.playClip(data, progress: { [weak self] p in self?.playProgress = p }) { c.resume() }
            }
            if playingClipId == clip.id { playingClipId = nil }
        } catch {
            errorMessage = "Couldn't load that clip."
        }
    }

    func playMissed(_ channelId: String) async {
        let missed = (clips[channelId] ?? []).filter { !playedClips.contains($0.id) && $0.userId != me?.id }.reversed()
        for c in missed { await play(c) }
    }

    // MARK: SOS

    func sendSOS() async {
        guard let api else { return }
        let loc = await Location.shared.current()
        do {
            let s = try await api.sendSOS(lat: loc?.coordinate.latitude, lng: loc?.coordinate.longitude, accuracy: loc?.horizontalAccuracy)
            mySOS = s
            Haptics.error()
            beginTalk(.sos, sosId: s.id) // records a 15-second clip for the family
            startSOSLocationUpdates()
        } catch {
            errorMessage = "SOS failed to send: \(error.localizedDescription). Call 112."
        }
    }

    private func startSOSLocationUpdates() {
        Location.shared.onUpdate = { [weak self] l in
            guard let self, let s = self.mySOS, s.isActive else { return }
            Task { try? await self.api?.sosLocation(s.id, lat: l.coordinate.latitude, lng: l.coordinate.longitude, accuracy: l.horizontalAccuracy) }
        }
        Location.shared.startTracking()
    }

    func resolveMySOS(falseAlarm: Bool) async {
        guard let s = mySOS else { return }
        try? await api?.sosResolve(s.id, falseAlarm: falseAlarm)
        Location.shared.stopTracking()
        mySOS = nil
        await loadSOS()
    }

    func respond(to s: SOSEvent) async {
        try? await api?.sosRespond(s.id)
    }

    private func showIncoming(_ s: SOSEvent) {
        incomingSOS = s
        Haptics.error()
        Task { try? await api?.sosAck(s.id) }
    }

    private func handleSOSUpdate(_ s: SOSEvent) {
        if s.userId == me?.id {
            mySOS = s.isActive ? s : nil
            if !s.isActive { Location.shared.stopTracking() }
        } else if s.isActive {
            if incomingSOS?.id == s.id { incomingSOS = s } else if incomingSOS == nil { showIncoming(s) }
        } else if incomingSOS?.id == s.id {
            incomingSOS = s // shows "safe" state; user dismisses
        }
        if let i = sosHistory.firstIndex(where: { $0.id == s.id }) { sosHistory[i] = s } else { sosHistory.insert(s, at: 0) }
    }

    func name(of userId: String) -> String {
        for c in channels { if let m = c.members.first(where: { $0.id == userId }) { return m.name } }
        return "Someone"
    }

    func member(_ userId: String) -> Member? {
        for c in channels { if let m = c.members.first(where: { $0.id == userId }) { return m } }
        return nil
    }

    // MARK: notifications

    private func registerNotifications() async {
        let center = UNUserNotificationCenter.current()
        let respond = UNNotificationAction(identifier: "RESPOND", title: "I'm responding", options: [.foreground])
        center.setNotificationCategories([UNNotificationCategory(identifier: "SOS", actions: [respond], intentIdentifiers: [])])
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        UIApplication.shared.registerForRemoteNotifications()
    }

    func didRegisterAlertToken(_ token: String) {
        alertToken = token
        Task { try? await api?.setPushTokens(alert: token, sandbox: AppModel.apnsSandbox) }
    }

    static var apnsSandbox: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}

// MARK: PushToTalk delegate

extension AppModel: PTTControllerDelegate {
    func pttDidJoin(channelId: String) {
        if selectedChannelId != channelId, channels.contains(where: { $0.id == channelId }) { selectedChannelId = channelId }
        ptt.setConnected(connected)
    }

    func pttDidLeave() {
        if onDuty && phase == .ready { onDuty = false } // left from the system UI
    }

    func pttJoinFailed(_ message: String) {
        // Stay on duty with the server (you still hear people while Hollo is
        // open); only the lock-screen button is missing. Retry on next change.
        print("Push to Talk unavailable: \(message)")
    }

    func pttDidBeginTransmitting(fromSystemUI: Bool) {
        if fromSystemUI && talk == .idle {
            // Lock-screen / Dynamic Island talk button.
            guard let ch = selectedChannel else { return }
            talk = .requesting(.hold)
            gate.request()
            relay?.connect()
            relay?.requestFloor(channel: ch.id, mode: .hold)
        }
    }

    func pttDidEndTransmitting() {
        if talk.isActive {
            relay?.releaseFloor()
            audio.stopCapture()
            talk = .idle
            grantedTx = nil
            gate.reset()
            talkTimer?.invalidate()
            level = 0
        }
    }

    func pttAudioActivated() {
        sessionActive = true
        audio.sessionActivated()
        if talk.isActive { audio.startCapture() }
        if !heldRemote.isEmpty {
            audio.play(packets: heldRemote)
            heldRemote.removeAll()
        }
    }

    func pttAudioDeactivated() {
        sessionActive = false
        audio.sessionDeactivated()
    }

    func pttPushToken(_ token: String) {
        Task { try? await api?.setPushTokens(ptt: token, sandbox: AppModel.apnsSandbox) }
    }

    func pttIncomingPush(speaker: String, channelId: String, txId: UInt32) {
        // Woken in the background: connect so the relay replays the live audio.
        if relay == nil, phase != .ready {
            Task { await start() }
        } else {
            relay?.connect()
        }
    }
}

/// Thread-safe hand-off between the audio thread and the relay: holds up to
/// ~1.2 s of pre-roll until the server grants the floor, then streams.
final class TxGate {
    weak var relay: Relay?
    private let lock = NSLock()
    private var state = 0 // 0 idle, 1 requesting, 2 granted
    private var pre: [[Data]] = []

    func request() { lock.lock(); state = 1; pre.removeAll(); lock.unlock() }
    func reset() { lock.lock(); state = 0; pre.removeAll(); lock.unlock() }

    func grant() {
        lock.lock()
        for b in pre { relay?.sendAudio(b) }
        pre.removeAll()
        state = 2
        lock.unlock()
    }

    func push(_ packets: [Data]) {
        lock.lock()
        switch state {
        case 2: relay?.sendAudio(packets)
        case 1: if pre.count < 20 { pre.append(packets) }
        default: break
        }
        lock.unlock()
    }
}

/// Last good copies of server data, so the app opens even when the server
/// can't be reached. Cleared when leaving the family.
enum LocalCache {
    static func save<T: Encodable>(_ value: T, _ key: String) {
        if let d = try? JSONEncoder().encode(value) { UserDefaults.standard.set(d, forKey: "cache." + key) }
    }
    static func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        UserDefaults.standard.data(forKey: "cache." + key).flatMap { try? JSONDecoder().decode(type, from: $0) }
    }
    static func clear() {
        for k in ["me", "settings", "channels", "clips"] { UserDefaults.standard.removeObject(forKey: "cache." + k) }
    }
}
