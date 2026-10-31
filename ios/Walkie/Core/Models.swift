import Foundation

// Shapes match docs/PROTOCOL.md. Keep both in sync.

struct Member: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var role: String
    var status: String
    var platform: String
    var phone: String?
    var online: Bool
    var duty: Bool

    var isAdmin: Bool { role == "admin" }
    var isPending: Bool { status == "pending" }
    var initials: String { Initials.of(name) }
}

struct Channel: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var color: String
    var isDefault: Bool
    var kind: String?
    var members: [Member]

    /// A personal channel: exactly two people; nobody else (not even the admin) can hear or replay it.
    var isPersonal: Bool { kind == "direct" }

    /// Personal channels are named after the other person.
    func title(me: String?) -> String {
        guard isPersonal else { return name }
        return members.first { $0.id != me }?.name ?? "Personal"
    }

    func other(me: String?) -> Member? { isPersonal ? members.first { $0.id != me } : nil }
}

struct Settings: Codable, Equatable {
    var clipTtlHours: Int
}

struct Clip: Codable, Identifiable, Hashable {
    let id: String
    let channelId: String
    let userId: String
    let userName: String
    let mode: String
    let sosId: String?
    let startedAt: Date
    let durationMs: Int
    let expiresAt: Date?
}

struct SOSLocation: Codable, Hashable {
    let lat: Double
    let lng: Double
    let accuracy: Double
    let at: Date
}

struct SOSEvent: Codable, Identifiable, Hashable {
    let id: String
    let userId: String
    let userName: String
    let channelId: String
    let createdAt: Date
    var state: String
    var resolution: String?
    var resolvedAt: Date?
    var location: SOSLocation?
    var recipients: [String]
    var acked: [String]
    var responders: [String]
    var clipIds: [String]

    var isActive: Bool { state == "active" }
}

struct InviteResult: Codable {
    struct Info: Codable { let id: String; let channelId: String; let label: String; let expiresAt: Date }
    let invite: Info
    let code: String
    let url: String
    let appUrl: String
}

struct PendingInvite: Codable, Identifiable {
    let id: String
    let channelId: String
    let label: String
    let createdAt: Date
    let expiresAt: Date
}

enum Initials {
    static func of(_ name: String) -> String {
        let parts = name.split(separator: " ").filter { !$0.isEmpty }
        if parts.count >= 2 { return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased() }
        let word = parts.first.map(String.init) ?? "?"
        return String(word.prefix(word.count > 3 ? 1 : 2)).capitalized
    }
}

/// Talk modes. Limits mirror the server's.
enum TalkMode: String {
    case hold, handsfree, sos

    var limit: TimeInterval {
        switch self {
        case .hold: return 120
        case .handsfree: return 60
        case .sos: return 15
        }
    }
}
