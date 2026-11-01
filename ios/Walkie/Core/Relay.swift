import Foundation
import Network

/// Events from the relay, delivered on the main actor.
enum RelayEvent {
    case connected
    case disconnected
    case welcome(active: [(channel: String, txId: UInt32, userId: String)])
    case floorGranted(channel: String, txId: UInt32, maxMs: Int)
    case floorDenied(channel: String, reason: String)
    case floorTaken(channel: String, txId: UInt32, userId: String, name: String, mode: String)
    case floorEnded(channel: String, txId: UInt32, userId: String, clipId: String?, reason: String)
    case audio(txId: UInt32, packets: [Data])
    case presence(userId: String, online: Bool, duty: Bool)
    case sos(SOSEvent)
    case channelsChanged
    case usersChanged
    case settingsChanged(Settings)
}

/// WebSocket connection to the server with automatic reconnect.
/// Battery: no polling; one app-level ping every 4 minutes keeps NAT open.
final class Relay: NSObject, URLSessionWebSocketDelegate {
    var onEvent: ((RelayEvent) -> Void)?
    var duty = true
    private(set) var isConnected = false

    private let url: URL
    private let token: String
    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private var wanted = false
    private var backoff: TimeInterval = 1
    private var pingTimer: Timer?
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "walkie.relay")

    init(serverURL: URL, token: String) {
        var comps = URLComponents(url: serverURL.appendingPathComponent("v1/ws"), resolvingAgainstBaseURL: false)!
        comps.scheme = serverURL.scheme == "http" ? "ws" : "wss"
        self.url = comps.url!
        self.token = token
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self, path.status == .satisfied else { return }
            DispatchQueue.main.async { if self.wanted && !self.isConnected { self.backoff = 1; self.open() } }
        }
        monitor.start(queue: queue)
    }

    func connect() {
        wanted = true
        if task == nil { open() }
    }

    func disconnect() {
        wanted = false
        pingTimer?.invalidate()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        isConnected = false
    }

    private func open() {
        task?.cancel(with: .goingAway, reason: nil)
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let t = session.webSocketTask(with: req)
        t.maximumMessageSize = 1 << 20
        task = t
        t.resume()
        receive(on: t)
        send(json: ["t": "hello", "duty": duty, "app": "ios \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] ?? "")"])
    }

    func setDuty(_ on: Bool) {
        duty = on
        send(json: ["t": "duty", "on": on])
    }

    func requestFloor(channel: String, mode: TalkMode, sosId: String? = nil) {
        var m: [String: Any] = ["t": "floor_request", "channel": channel, "mode": mode.rawValue]
        if let sosId { m["sosId"] = sosId }
        send(json: m)
    }

    func releaseFloor() { send(json: ["t": "floor_release"]) }

    /// Diagnostics: the server writes these to its log.
    func log(_ msg: String) { send(json: ["t": "log", "msg": msg]) }

    /// Sends up to 3 Opus packets in one message (protocol: 0x01, then u16 length + bytes).
    func sendAudio(_ packets: [Data]) {
        var d = Data([0x01])
        for p in packets {
            var len = UInt16(p.count).bigEndian
            d.append(Data(bytes: &len, count: 2))
            d.append(p)
        }
        task?.send(.data(d)) { _ in }
    }

    private func send(json: [String: Any]) {
        guard let task, let d = try? JSONSerialization.data(withJSONObject: json), let s = String(data: d, encoding: .utf8) else { return }
        task.send(.string(s)) { _ in }
    }

    private func receive(on t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self, t === self.task else { return }
            switch result {
            case .failure:
                DispatchQueue.main.async { self.handleDrop() }
            case .success(let msg):
                switch msg {
                case .data(let d): self.handleBinary(d)
                case .string(let s): self.handleText(s)
                @unknown default: break
                }
                self.receive(on: t)
            }
        }
    }

    private func handleDrop() {
        let was = isConnected
        isConnected = false
        task = nil
        pingTimer?.invalidate()
        if was { onEvent?(.disconnected) }
        guard wanted else { return }
        let delay = backoff
        backoff = min(backoff * 2, 30)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.wanted, self.task == nil else { return }
            self.open()
        }
    }

    private func handleBinary(_ d: Data) {
        guard d.count > 5, d[d.startIndex] == 0x01 else { return }
        let bytes = [UInt8](d)
        let tx = UInt32(bytes[1]) << 24 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 8 | UInt32(bytes[4])
        var packets: [Data] = []
        var i = 5
        while i + 2 <= bytes.count {
            let len = Int(bytes[i]) << 8 | Int(bytes[i + 1])
            i += 2
            guard len > 0, i + len <= bytes.count else { break }
            packets.append(Data(bytes[i..<i + len]))
            i += len
        }
        DispatchQueue.main.async { self.onEvent?(.audio(txId: tx, packets: packets)) }
    }

    private func handleText(_ s: String) {
        guard let d = s.data(using: .utf8), let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], let t = m["t"] as? String else { return }
        let ch = m["channel"] as? String ?? ""
        let tx = (m["txId"] as? NSNumber)?.uint32Value ?? 0
        var ev: RelayEvent?
        switch t {
        case "welcome":
            let active = (m["active"] as? [[String: Any]] ?? []).map {
                (channel: $0["channel"] as? String ?? "", txId: ($0["txId"] as? NSNumber)?.uint32Value ?? 0, userId: $0["userId"] as? String ?? "")
            }
            DispatchQueue.main.async {
                self.isConnected = true
                self.backoff = 1
                self.startPing()
                self.onEvent?(.connected)
            }
            ev = .welcome(active: active)
        case "floor_granted": ev = .floorGranted(channel: ch, txId: tx, maxMs: (m["maxMs"] as? NSNumber)?.intValue ?? 0)
        case "floor_denied": ev = .floorDenied(channel: ch, reason: m["reason"] as? String ?? "")
        case "floor_taken": ev = .floorTaken(channel: ch, txId: tx, userId: m["userId"] as? String ?? "", name: m["name"] as? String ?? "", mode: m["mode"] as? String ?? "hold")
        case "floor_ended":
            let clip = m["clipId"] as? String
            ev = .floorEnded(channel: ch, txId: tx, userId: m["userId"] as? String ?? "", clipId: (clip?.isEmpty ?? true) ? nil : clip, reason: m["reason"] as? String ?? "")
        case "presence": ev = .presence(userId: m["userId"] as? String ?? "", online: m["online"] as? Bool ?? false, duty: m["duty"] as? Bool ?? false)
        case "sos":
            if let raw = m["sos"], let rd = try? JSONSerialization.data(withJSONObject: raw), let sos = try? API.decoder.decode(SOSEvent.self, from: rd) {
                ev = .sos(sos)
            }
        case "channels_changed": ev = .channelsChanged
        case "users_changed": ev = .usersChanged
        case "settings_changed":
            if let raw = m["settings"], let rd = try? JSONSerialization.data(withJSONObject: raw), let st = try? API.decoder.decode(Settings.self, from: rd) {
                ev = .settingsChanged(st)
            }
        default: break
        }
        if let ev { DispatchQueue.main.async { self.onEvent?(ev) } }
    }

    private func startPing() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 240, repeats: true) { [weak self] _ in
            self?.send(json: ["t": "ping"])
        }
        pingTimer?.tolerance = 30 // let iOS batch wake-ups
    }
}
