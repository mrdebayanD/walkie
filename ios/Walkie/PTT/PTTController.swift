import AVFoundation
import CryptoKit
import Foundation
import PushToTalk
import UIKit

/// Wraps Apple's PushToTalk framework (iOS 16+). Being "on duty" means our
/// current channel is joined as a system PTT channel: the lock screen shows a
/// talk button, and a PTT push wakes the app when someone starts talking.
@MainActor
protocol PTTControllerDelegate: AnyObject {
    func pttDidJoin(channelId: String)
    func pttDidLeave()
    func pttJoinFailed(_ message: String)
    func pttTransmitFailed(_ message: String)
    func pttDidBeginTransmitting(fromSystemUI: Bool)
    func pttDidEndTransmitting()
    func pttAudioActivated()
    func pttAudioDeactivated()
    func pttPushToken(_ token: String)
    /// A PTT push woke us: make sure the relay is connected.
    func pttIncomingPush(speaker: String, channelId: String, txId: UInt32)
}

@MainActor
final class PTTController: NSObject {
    weak var delegate: PTTControllerDelegate?

    private var manager: PTChannelManager?
    private(set) var joinedUUID: UUID?
    private(set) var joinedChannelId: String?
    private var channelNames: [UUID: (id: String, name: String)] = [:]

    static func uuid(for channelId: String) -> UUID {
        // Stable UUID per channel id (first 16 bytes of SHA-256).
        let d = Array(SHA256.hash(data: Data(("walkie:" + channelId).utf8)))
        return UUID(uuid: (d[0], d[1], d[2], d[3], d[4], d[5], (d[6] & 0x0f) | 0x50, d[7], (d[8] & 0x3f) | 0x80, d[9], d[10], d[11], d[12], d[13], d[14], d[15]))
    }

    /// Must run at launch (also on background launches) so restored channels
    /// and PTT pushes are handled.
    func setUp() async {
        guard manager == nil else { return }
        do {
            manager = try await PTChannelManager.channelManager(delegate: self, restorationDelegate: self)
            reconcile() // a join requested before the manager was ready
        } catch {
            print("PTT manager failed: \(error)")
        }
    }

    /// The channel we want to be in (nil = off duty). Apple's join/leave calls
    /// are asynchronous, so requests made while one is in flight are replayed
    /// once it completes instead of being dropped.
    private var wanted: (id: String, name: String)?
    private var leavingUUID: UUID?
    private var joiningUUID: UUID?

    func join(channelId: String, name: String) {
        let uuid = PTTController.uuid(for: channelId)
        channelNames[uuid] = (channelId, name)
        UserDefaults.standard.set([channelId, name], forKey: "ptt.channel.\(uuid.uuidString)")
        wanted = (channelId, name)
        reconcile()
    }

    func leave() {
        wanted = nil
        reconcile()
    }

    /// Moves Apple's PTT state one step toward `wanted`.
    private func reconcile() {
        guard let manager, leavingUUID == nil, joiningUUID == nil else { return } // wait for the in-flight call
        let target = wanted.map { PTTController.uuid(for: $0.id) }
        if let current = joinedUUID {
            if current == target { return }
            leavingUUID = current
            manager.leaveChannel(channelUUID: current)
        } else if let target, let w = wanted {
            joiningUUID = target
            let descriptor = PTChannelDescriptor(name: w.name, image: UIImage(named: "ChannelImage"))
            manager.requestJoinChannel(channelUUID: target, descriptor: descriptor)
        }
    }

    func beginTransmitting() {
        guard let manager, let uuid = joinedUUID else { return }
        manager.requestBeginTransmitting(channelUUID: uuid)
    }

    func stopTransmitting() {
        guard let manager, let uuid = joinedUUID else { return }
        manager.stopTransmitting(channelUUID: uuid)
    }

    /// Shows who is talking in the system UI and makes the system activate
    /// the audio session for playback. nil when they stop.
    func setRemoteSpeaker(_ name: String?) {
        guard let manager, let uuid = joinedUUID else { return }
        let p = name.map { PTParticipant(name: $0, image: nil) }
        manager.setActiveRemoteParticipant(p, channelUUID: uuid) { error in
            if let error { print("setActiveRemoteParticipant: \(error)") }
        }
    }

    func setConnected(_ connected: Bool) {
        guard let manager, let uuid = joinedUUID else { return }
        manager.setServiceStatus(connected ? .ready : .connecting, channelUUID: uuid) { _ in }
    }

    var isJoined: Bool { joinedUUID != nil }
}

extension PTTController: PTChannelManagerDelegate {
    nonisolated func channelManager(_ channelManager: PTChannelManager, didJoinChannel channelUUID: UUID, reason: PTChannelJoinReason) {
        Task { @MainActor in
            self.joiningUUID = nil
            self.joinedUUID = channelUUID
            let saved = self.channelNames[channelUUID] ?? self.restoredName(channelUUID)
            self.joinedChannelId = saved?.id
            if reason != .developerRequest, self.wanted == nil, let saved { self.wanted = saved }
            if let id = saved?.id { self.delegate?.pttDidJoin(channelId: id) }
            self.reconcile() // the user may have changed their mind meanwhile
        }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, didLeaveChannel channelUUID: UUID, reason: PTChannelLeaveReason) {
        Task { @MainActor in
            let ours = self.leavingUUID == channelUUID
            self.leavingUUID = nil
            if self.joinedUUID == channelUUID {
                self.joinedUUID = nil
                self.joinedChannelId = nil
            }
            if !ours && reason != .developerRequest {
                // Left from the lock screen / system UI: that means "off duty".
                self.wanted = nil
                self.delegate?.pttDidLeave()
            }
            self.reconcile()
        }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didBeginTransmittingFrom source: PTChannelTransmitRequestSource) {
        Task { @MainActor in self.delegate?.pttDidBeginTransmitting(fromSystemUI: source != .developerRequest) }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didEndTransmittingFrom source: PTChannelTransmitRequestSource) {
        Task { @MainActor in self.delegate?.pttDidEndTransmitting() }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, receivedEphemeralPushToken pushToken: Data) {
        let hex = pushToken.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in self.delegate?.pttPushToken(hex) }
    }

    nonisolated func incomingPushResult(channelManager: PTChannelManager, channelUUID: UUID, pushPayload: [String: Any]) -> PTPushResult {
        // Must return quickly: tell the system who is talking; audio comes over the relay.
        let speaker = pushPayload["speaker"] as? String ?? "Family"
        let channel = pushPayload["channel"] as? String ?? ""
        let tx = (pushPayload["txId"] as? NSNumber)?.uint32Value ?? 0
        Task { @MainActor in self.delegate?.pttIncomingPush(speaker: speaker, channelId: channel, txId: tx) }
        return .activeRemoteParticipant(PTParticipant(name: speaker, image: nil))
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, didActivate audioSession: AVAudioSession) {
        Task { @MainActor in self.delegate?.pttAudioActivated() }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in self.delegate?.pttAudioDeactivated() }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, failedToJoinChannel channelUUID: UUID, error: Error) {
        print("PTT join failed: \(error)")
        Task { @MainActor in
            self.joiningUUID = nil
            self.delegate?.pttJoinFailed(error.localizedDescription)
        }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, failedToBeginTransmittingInChannel channelUUID: UUID, error: Error) {
        print("PTT transmit failed: \(error)")
        Task { @MainActor in self.delegate?.pttTransmitFailed(error.localizedDescription) }
        Task { @MainActor in self.delegate?.pttDidEndTransmitting() }
    }

    nonisolated func channelManager(_ channelManager: PTChannelManager, failedToStopTransmittingInChannel channelUUID: UUID, error: Error) {
        print("PTT stop failed: \(error)")
    }
}

extension PTTController: PTChannelRestorationDelegate {
    nonisolated func channelDescriptor(restoredChannelUUID channelUUID: UUID) -> PTChannelDescriptor {
        let saved = UserDefaults.standard.stringArray(forKey: "ptt.channel.\(channelUUID.uuidString)")
        return PTChannelDescriptor(name: saved?.last ?? "Family", image: UIImage(named: "ChannelImage"))
    }

    fileprivate func restoredName(_ uuid: UUID) -> (id: String, name: String)? {
        guard let saved = UserDefaults.standard.stringArray(forKey: "ptt.channel.\(uuid.uuidString)"), saved.count == 2 else { return nil }
        return (saved[0], saved[1])
    }
}
