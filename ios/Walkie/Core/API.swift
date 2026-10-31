import Foundation

struct APIError: LocalizedError {
    let status: Int
    let code: String
    let message: String
    var errorDescription: String? { message }
}

/// Thin REST client for the Walkie server.
final class API {
    let baseURL: URL
    var token: String?

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    init(baseURL: URL, token: String?) {
        self.baseURL = baseURL
        self.token = token
    }

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            if let date = parseDate(s) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "Bad date \(s)"))
        }
        return d
    }()

    /// Go writes RFC 3339 with up to 9 fractional digits; trim to 3 for Foundation.
    static func parseDate(_ s: String) -> Date? {
        var str = s
        if let dot = str.firstIndex(of: "."), let end = str[dot...].firstIndex(where: { $0 == "Z" || $0 == "+" || $0 == "-" }) {
            let frac = str[str.index(after: dot)..<end]
            str.replaceSubrange(str.index(after: dot)..<end, with: String(frac.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0))
        }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: str) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: str)
    }

    func request<T: Decodable>(_ method: String, _ path: String, body: [String: Any]? = nil, as: T.Type = T.self) async throws -> T {
        let data = try await raw(method, path, body: body)
        return try API.decoder.decode(T.self, from: data)
    }

    @discardableResult
    func raw(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> Data {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            throw APIError(status: status,
                           code: obj?["error"] as? String ?? "http_\(status)",
                           message: obj?["message"] as? String ?? "The server answered \(status).")
        }
        return data
    }

    // MARK: endpoints

    struct JoinResponse: Decodable { let token: String; let user: Member }
    struct MeResponse: Decodable { let user: Member; let settings: Settings }
    struct ChannelsResponse: Decodable { let channels: [Channel] }
    struct ChannelResponse: Decodable { let channel: Channel }
    struct UsersResponse: Decodable { let users: [Member] }
    struct UserResponse: Decodable { let user: Member }
    struct ClipsResponse: Decodable { let clips: [Clip] }
    struct SettingsResponse: Decodable { let settings: Settings }
    struct SOSResponse: Decodable { let sos: SOSEvent }
    struct SOSListResponse: Decodable { let sos: [SOSEvent] }
    struct InvitesResponse: Decodable { let invites: [PendingInvite] }

    func join(code: String, name: String, phone: String) async throws -> JoinResponse {
        try await request("POST", "v1/join", body: ["code": code, "name": name, "phone": phone, "platform": "ios"])
    }
    /// Opens (or returns) the personal channel with another member.
    func personalChannel(with userId: String) async throws -> Channel {
        try await request("POST", "v1/direct", body: ["userId": userId], as: ChannelResponse.self).channel
    }
    /// Admin: fix someone's name or phone number.
    func updateUser(_ id: String, name: String? = nil, phone: String? = nil) async throws {
        var b: [String: Any] = [:]
        if let name { b["name"] = name }
        if let phone { b["phone"] = phone }
        try await raw("PUT", "v1/users/\(id)", body: b)
    }
    func me() async throws -> MeResponse { try await request("GET", "v1/me") }
    func updateMe(name: String? = nil, phone: String? = nil) async throws -> Member {
        var b: [String: Any] = [:]
        if let name { b["name"] = name }
        if let phone { b["phone"] = phone }
        return try await request("PUT", "v1/me", body: b, as: UserResponse.self).user
    }
    func setPushTokens(ptt: String? = nil, alert: String? = nil, sandbox: Bool) async throws {
        var b: [String: Any] = ["apnsSandbox": sandbox]
        if let ptt { b["apnsPtt"] = ptt }
        if let alert { b["apnsAlert"] = alert }
        try await raw("PUT", "v1/me/push", body: b)
    }
    func channels() async throws -> [Channel] { try await request("GET", "v1/channels", as: ChannelsResponse.self).channels }
    func users() async throws -> [Member] { try await request("GET", "v1/users", as: UsersResponse.self).users }
    func approve(_ id: String) async throws { try await raw("POST", "v1/users/\(id)/approve") }
    func removeUser(_ id: String) async throws { try await raw("DELETE", "v1/users/\(id)") }
    func createChannel(name: String, color: String, memberIds: [String]) async throws -> Channel {
        try await request("POST", "v1/channels", body: ["name": name, "color": color, "memberIds": memberIds], as: ChannelResponse.self).channel
    }
    func updateChannel(_ id: String, name: String?, color: String?) async throws {
        var b: [String: Any] = [:]
        if let name { b["name"] = name }
        if let color { b["color"] = color }
        try await raw("PATCH", "v1/channels/\(id)", body: b)
    }
    func setMembers(_ id: String, memberIds: [String]) async throws {
        try await raw("PUT", "v1/channels/\(id)/members", body: ["memberIds": memberIds])
    }
    func deleteChannel(_ id: String) async throws { try await raw("DELETE", "v1/channels/\(id)") }
    func createInvite(channelId: String, label: String) async throws -> InviteResult {
        try await request("POST", "v1/invites", body: ["channelId": channelId, "label": label])
    }
    func invites() async throws -> [PendingInvite] { try await request("GET", "v1/invites", as: InvitesResponse.self).invites }
    func deleteInvite(_ id: String) async throws { try await raw("DELETE", "v1/invites/\(id)") }
    func clips(channel: String) async throws -> [Clip] { try await request("GET", "v1/channels/\(channel)/clips", as: ClipsResponse.self).clips }
    func clipAudio(_ id: String) async throws -> Data { try await raw("GET", "v1/clips/\(id)/audio") }
    func settings() async throws -> Settings { try await request("GET", "v1/settings", as: SettingsResponse.self).settings }
    func setClipTTL(hours: Int) async throws -> Settings {
        try await request("PUT", "v1/settings", body: ["clipTtlHours": hours], as: SettingsResponse.self).settings
    }
    func sendSOS(lat: Double?, lng: Double?, accuracy: Double?) async throws -> SOSEvent {
        var b: [String: Any] = [:]
        if let lat, let lng { b["lat"] = lat; b["lng"] = lng; b["accuracy"] = accuracy ?? 0 }
        return try await request("POST", "v1/sos", body: b, as: SOSResponse.self).sos
    }
    func sosList() async throws -> [SOSEvent] { try await request("GET", "v1/sos", as: SOSListResponse.self).sos }
    func sosLocation(_ id: String, lat: Double, lng: Double, accuracy: Double) async throws {
        try await raw("POST", "v1/sos/\(id)/location", body: ["lat": lat, "lng": lng, "accuracy": accuracy])
    }
    func sosAck(_ id: String) async throws { try await raw("POST", "v1/sos/\(id)/ack") }
    func sosRespond(_ id: String) async throws { try await raw("POST", "v1/sos/\(id)/respond") }
    func sosResolve(_ id: String, falseAlarm: Bool) async throws {
        try await raw("POST", "v1/sos/\(id)/resolve", body: ["resolution": falseAlarm ? "false_alarm" : "safe"])
    }
    func deleteSOS(_ id: String) async throws { try await raw("DELETE", "v1/sos/\(id)") }
}
