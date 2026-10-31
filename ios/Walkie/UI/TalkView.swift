import SwiftUI

struct TalkView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let ch = model.selectedChannel
        VStack(spacing: 0) {
            header(ch)
            if let ch, ch.isPersonal { privateBar(ch) }
            VStack(alignment: .leading, spacing: 2) {
                Text(titleText).font(.largeTitle.bold())
                    .contentTransition(.opacity)
                Text(subtitle(ch)).font(.subheadline).foregroundStyle(Theme.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 22)

            members(ch)

            Spacer(minLength: 12)
            center
            Spacer(minLength: 12)

            lastMessageCard
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .background(alignment: .center) { glow }
        .animation(.easeOut(duration: 0.25), value: model.talk)
        .animation(.easeOut(duration: 0.25), value: model.remote)
    }

    private var titleText: String {
        if case .talking = model.talk { return "You're talking" }
        if let r = model.remote { return "\(r.name) is talking" }
        return model.selectedChannel.map(model.title) ?? "Hollo"
    }

    private func subtitle(_ ch: Channel?) -> String {
        guard let ch else { return "" }
        if ch.isPersonal, !model.talk.isActive {
            let other = ch.other(me: model.me?.id)
            return "Personal · only you and \(other?.name ?? "them") · \(other?.duty == true ? "on duty" : "off duty")"
        }
        let others = ch.members.filter { $0.id != model.me?.id }
        if case .talking = model.talk {
            let n = others.filter(\.duty).count
            return n == 1 ? "1 person is hearing you" : "\(n) people are hearing you"
        }
        if !model.connected { return "Connecting…" }
        let on = ch.members.filter { $0.id == model.me?.id ? model.onDuty : $0.duty }.count
        return "\(on) of \(ch.members.count) on duty"
    }

    private func header(_ ch: Channel?) -> some View {
        HStack {
            Menu {
                Picker("Channel", selection: $model.selectedChannelId) {
                    ForEach(model.groupChannels) { c in Text(c.name).tag(Optional(c.id)) }
                }
                if !model.personalChannels.isEmpty {
                    Section("Personal") {
                        Picker("Personal", selection: $model.selectedChannelId) {
                            ForEach(model.personalChannels) { c in Label(model.title(c), systemImage: "lock.fill").tag(Optional(c.id)) }
                        }
                    }
                }
                Section {
                    Button { model.selectedTab = 2 } label: { Label("Talk privately with…", systemImage: "lock") }
                }
            } label: {
                HStack(spacing: 6) {
                    Circle().fill(model.connected ? Theme.green : Theme.orange).frame(width: 8, height: 8)
                    if ch?.isPersonal == true { Image(systemName: "lock.fill").font(.caption2) }
                    Text(ch.map(model.title) ?? "Channel").font(.subheadline.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16).frame(height: 44)
                .glass(22)
            }
            .accessibilityLabel("Channel: \(ch.map(model.title) ?? "none"). Change channel")
            Spacer()
            Button { model.speakerMuted.toggle(); Haptics.tick() } label: {
                Image(systemName: model.speakerMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .foregroundStyle(model.speakerMuted ? Theme.orange : .white)
                    .glass(22)
            }
            .accessibilityLabel(model.speakerMuted ? "Speaker muted on this phone. Unmute" : "Mute speaker on this phone")
        }
    }

    /// "Only Ma hears you" with a way back to the group channel.
    private func privateBar(_ ch: Channel) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "lock.fill").foregroundStyle(Theme.green)
            Text("Only \(model.title(ch)) hears you").font(.subheadline.weight(.semibold))
            Spacer()
            Button {
                model.backToGroup()
            } label: {
                Text("Back to \(model.returnChannel.map(model.title) ?? "Family")").font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(Theme.green)
            .frame(minHeight: 44)
        }
        .padding(.horizontal, 14)
        .glass(18)
        .padding(.top, 12)
    }

    /// Tap someone to talk to them only; tap yourself does nothing.
    private func members(_ ch: Channel?) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(ch?.members ?? []) { m in
                    let speaking = model.remote?.userId == m.id || (m.id == model.me?.id && model.talk != .idle)
                    let isMe = m.id == model.me?.id
                    Button {
                        guard !isMe, ch?.isPersonal != true else { return }
                        Haptics.tick()
                        Task { await model.talkPrivately(with: m) }
                    } label: {
                        VStack(spacing: 6) {
                            Avatar(member: m, size: 48, showStatus: true)
                                .overlay(Circle().strokeBorder(speaking ? Theme.green : .clear, lineWidth: 2.5).padding(-4))
                            Text(isMe ? "You" : m.name)
                                .font(.caption).foregroundStyle(Theme.secondary).lineLimit(1)
                        }
                        .frame(width: 56)
                    }
                    .buttonStyle(.plain)
                    .disabled(isMe || ch?.isPersonal == true || model.talk.isActive)
                    .accessibilityHint(isMe || ch?.isPersonal == true ? "" : "Talk to \(m.name) only")
                }
            }
        }
        .padding(.top, 20)
        .overlay(alignment: .bottomLeading) {
            if ch?.isPersonal != true && (ch?.members.count ?? 0) > 2 {
                Text("Tap someone to talk to them only").font(.caption2).foregroundStyle(Theme.secondary).offset(y: 18)
            }
        }
    }

    @ViewBuilder private var center: some View {
        if let r = model.remote {
            VStack(spacing: 24) {
                ZStack {
                    Circle().strokeBorder(Theme.orange.opacity(0.2), lineWidth: 1.5).frame(width: 260, height: 260)
                    Circle().strokeBorder(Theme.orange.opacity(0.38), lineWidth: 1.5).frame(width: 208, height: 208)
                    Avatar(member: model.member(r.userId), name: r.name, size: 156)
                        .shadow(color: Theme.orange.opacity(0.35), radius: 30)
                }
                Waveform(level: 0.8, color: Theme.orange)
                Label("Busy — wait for \(r.name) to finish", systemImage: "mic.slash")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Theme.secondary)
                    .padding(.horizontal, 20).frame(height: 52).glass(26)
            }
            .accessibilityElement(children: .combine)
        } else {
            VStack(spacing: 22) {
                TalkButton()
                if case .talking = model.talk {
                    Waveform(level: model.level, color: Theme.green)
                } else {
                    Text("Press and hold to talk.\nDouble-tap for hands-free — tap again to stop.")
                        .font(.footnote).multilineTextAlignment(.center).foregroundStyle(Theme.secondary)
                }
            }
        }
    }

    @ViewBuilder private var lastMessageCard: some View {
        if let last = model.lastMessage, let clipId = last.clipId, let ch = model.selectedChannel,
           let clip = model.clips[ch.id]?.first(where: { $0.id == clipId }) {
            Button { Task { await model.play(clip) } } label: {
                HStack(spacing: 12) {
                    Avatar(member: model.member(clip.userId), name: clip.userName, size: 36)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(clip.userId == model.me?.id ? "You" : clip.userName).font(.subheadline.weight(.semibold))
                        Text("\(clip.startedAt, style: .relative) ago · \(Durations.short(clip.durationMs))")
                            .font(.footnote).foregroundStyle(Theme.secondary)
                    }
                    Spacer()
                    Image(systemName: model.playingClipId == clip.id ? "pause.fill" : "play.fill")
                        .font(.footnote).foregroundStyle(Theme.green)
                        .frame(width: 36, height: 36).background(Theme.green.opacity(0.18), in: Circle())
                }
                .padding(12).glass(22)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Last message from \(clip.userName). Play")
        }
    }

    private var glow: some View {
        let color: Color = model.remote != nil ? Theme.orange : Theme.green
        let strength: Double = model.remote != nil ? 0.30 : (model.talk == .idle ? 0.14 : 0.38)
        return RadialGradient(colors: [color.opacity(strength), .clear], center: .center, startRadius: 0, endRadius: 280)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Press and hold to talk; double-tap for hands-free; single tap stops hands-free.
struct TalkButton: View {
    @EnvironmentObject var model: AppModel
    @State private var pressStart: Date?
    @State private var holdTask: Task<Void, Never>?
    @State private var holdingByPress = false
    @State private var lastTap: Date?
    @State private var tapTask: Task<Void, Never>?

    /// A short delay before a press starts talking, so brushing the screen doesn't transmit.
    private let holdDelay: TimeInterval = 0.3
    private let doubleTapWindow: TimeInterval = 0.35

    var body: some View {
        let active = model.talk.isActive
        let handsfree = model.talk.mode == .handsfree
        ZStack {
            if active {
                Circle().strokeBorder(Theme.green.opacity(0.22), lineWidth: 1.5).frame(width: 300, height: 300)
                Circle().strokeBorder(Theme.green.opacity(0.4), lineWidth: 1.5).frame(width: 256, height: 256)
            }
            Circle()
                .fill(active ? AnyShapeStyle(Theme.green) : AnyShapeStyle(Theme.green.opacity(0.14)))
                .overlay(Circle().strokeBorder(active ? .clear : Theme.green.opacity(0.85), lineWidth: 2))
                .frame(width: active ? 216 : 184, height: active ? 216 : 184)
                .shadow(color: Theme.green.opacity(active ? 0.55 : 0), radius: 35)
                .padding(24)
                .glass(140)
            VStack(spacing: 8) {
                Image(systemName: "mic.fill").font(.system(size: 46, weight: .medium))
                Text(label(active: active, handsfree: handsfree)).font(.headline)
                if handsfree { Text("Tap to stop").font(.footnote.weight(.semibold)) }
            }
            .foregroundStyle(active ? Theme.onGreen : .white)
        }
        .frame(width: 300, height: 300)
        .contentShape(Circle())
        .gesture(pressGesture)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(active ? (handsfree ? "Hands-free on. Double-tap to stop" : "Talking") : "Talk")
        .accessibilityHint("Double-tap to talk hands-free; tap again to stop.")
        .accessibilityAction { toggleHandsfree() }
    }

    private func label(active: Bool, handsfree: Bool) -> String {
        if handsfree { return "Hands-free" }
        if active { return model.talk == .requesting(.hold) ? "Connecting…" : "Release to send" }
        return "Hold to Talk"
    }

    private var pressGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard pressStart == nil else { return }
                pressStart = Date()
                if model.talk.mode == .handsfree { return } // a tap will stop it on release
                holdTask = Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64(holdDelay * 1_000_000_000))
                    guard !Task.isCancelled, pressStart != nil, model.talk == .idle else { return }
                    holdingByPress = true
                    tapTask?.cancel()
                    model.beginTalk(.hold)
                }
            }
            .onEnded { _ in
                let started = pressStart ?? Date()
                pressStart = nil
                holdTask?.cancel()
                if holdingByPress {
                    holdingByPress = false
                    model.endTalk()
                    return
                }
                guard Date().timeIntervalSince(started) < holdDelay else { return }
                // It was a tap.
                if model.talk.mode == .handsfree {
                    model.endTalk()
                    lastTap = nil
                    return
                }
                if let last = lastTap, Date().timeIntervalSince(last) < doubleTapWindow {
                    lastTap = nil
                    tapTask?.cancel()
                    toggleHandsfree()
                } else {
                    lastTap = Date()
                }
            }
    }

    private func toggleHandsfree() {
        if model.talk.isActive { model.endTalk() } else { model.beginTalk(.handsfree) }
    }
}

enum Durations {
    static func short(_ ms: Int) -> String {
        let s = max(1, Int((Double(ms) / 1000).rounded()))
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
