import MapKit
import SwiftUI

struct SafetyView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) var openURL
    @State private var progress: Double = 0
    @State private var holding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var showCountdown = false
    @State private var showProfile = false
    private let holdSeconds = 3.0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Button { openURL(URL(string: "tel:112")!) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "phone.fill").foregroundStyle(Theme.onGreen)
                            .frame(width: 36, height: 36).background(Theme.green, in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading) {
                            Text("In danger? Call 112 first").font(.headline).foregroundStyle(.white)
                            Text("India's emergency number").font(.footnote).foregroundStyle(Theme.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Theme.secondary)
                    }
                    .padding(14).background(Theme.card, in: RoundedRectangle(cornerRadius: 22))
                }
                .padding(.top, 8)

                if model.me?.phone?.isEmpty ?? true {
                    Button { showProfile = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.orange)
                            Text("Add your phone number so the family can call you during an SOS")
                                .font(.subheadline).foregroundStyle(.white).multilineTextAlignment(.leading)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(Theme.secondary)
                        }
                        .padding(14).background(Theme.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 22))
                    }
                    .padding(.top, 10)
                }

                Spacer()
                if let mine = model.mySOS {
                    activeSOS(mine)
                } else {
                    holdButton
                }
                Spacer()

                if model.mySOS == nil {
                VStack(alignment: .leading, spacing: 0) {
                    SectionHeader(text: "When you send SOS").padding(.bottom, 8)
                    VStack(spacing: 0) {
                        row("bell.fill", "All \(max(0, (model.defaultChannel?.members.count ?? 1) - 1)) family members get a loud alert")
                        Divider().padding(.leading, 44)
                        row("location.fill", "Your live location is shared")
                        Divider().padding(.leading, 44)
                        row("mic.fill", "A 15-second clip records automatically")
                    }
                    .padding(.horizontal, 14).background(Theme.card, in: RoundedRectangle(cornerRadius: 22))
                }
                .padding(.bottom, 12)
                }
            }
            .padding(.horizontal, 20)
            .navigationTitle("Safety")
            .fullScreenCover(isPresented: $showCountdown) { SOSCountdownView() }
            .sheet(isPresented: $showProfile) { ProfileView(member: nil) }
            .onAppear { Location.shared.requestPermission() }
        }
    }

    private func row(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(Theme.redText).frame(width: 22)
            Text(text).font(.subheadline)
            Spacer()
        }
        .padding(.vertical, 11)
    }

    private var holdButton: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle().stroke(Theme.red.opacity(0.18), lineWidth: 14)
                Circle().trim(from: 0, to: progress).stroke(Theme.red, style: StrokeStyle(lineWidth: 14, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Circle().fill(Color(red: 42 / 255, green: 10 / 255, blue: 8 / 255)).padding(14)
                VStack(spacing: 6) {
                    Text("SOS").font(.system(size: 44, weight: .heavy)).foregroundStyle(Theme.redText)
                    Text(holding ? "Keep holding…" : "Hold for 3 seconds").font(.subheadline.weight(.semibold))
                    if holding {
                        Text(String(format: "%.1f s left", max(0, holdSeconds * (1 - progress))))
                            .font(.footnote.monospacedDigit()).foregroundStyle(Theme.secondary)
                    }
                }
            }
            .frame(width: 236, height: 236)
            .contentShape(Circle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in startHold() }
                .onEnded { _ in cancelHold() })
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("SOS")
            .accessibilityHint("Opens a 5-second countdown to alert your whole family.")
            .accessibilityAction { showCountdown = true }

            Text("Press and hold for 3 seconds.\nYou get 5 more seconds to cancel.")
                .font(.footnote).multilineTextAlignment(.center).foregroundStyle(Theme.secondary)
        }
    }

    private func startHold() {
        guard !holding else { return }
        holding = true
        Haptics.tick()
        holdTask = Task { @MainActor in
            let start = Date()
            while !Task.isCancelled {
                progress = min(1, Date().timeIntervalSince(start) / holdSeconds)
                if progress >= 1 {
                    holding = false
                    progress = 0
                    showCountdown = true
                    return
                }
                try? await Task.sleep(nanoseconds: 30_000_000)
            }
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holding = false
        withAnimation(.easeOut(duration: 0.2)) { progress = 0 }
    }

    private func activeSOS(_ s: SOSEvent) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "sos.circle.fill").font(.system(size: 72)).foregroundStyle(Theme.red)
            Text("SOS sent").font(.title.bold())
            let n = s.responders.count
            Text(n == 0 ? "Alerting \(s.recipients.count) family members…" : "\(n) responding: \(s.responders.map { model.name(of: $0) }.joined(separator: ", "))")
                .foregroundStyle(Theme.secondary).multilineTextAlignment(.center)
            Button {
                Task { await model.resolveMySOS(falseAlarm: false) }
            } label: { Text("I'm safe").font(.headline).frame(maxWidth: .infinity, minHeight: 56) }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).foregroundStyle(Theme.onGreen)
            Button("It was a false alarm") { Task { await model.resolveMySOS(falseAlarm: true) } }
                .foregroundStyle(Theme.secondary)
            CallList(title: "Call someone", exclude: model.me?.id, firstIds: s.responders)
        }
    }
}

struct SOSCountdownView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var remaining = 5
    @State private var timerTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Color(red: 58 / 255, green: 9 / 255, blue: 7 / 255).ignoresSafeArea()
            VStack(spacing: 0) {
                Label("SOS", systemImage: "shield.fill").font(.footnote.weight(.bold))
                    .foregroundStyle(Color(red: 1, green: 180 / 255, blue: 174 / 255))
                    .padding(.horizontal, 14).frame(height: 32).background(Theme.red.opacity(0.24), in: Capsule())
                    .padding(.top, 40)
                Text("Sending SOS to your family").font(.title.bold()).multilineTextAlignment(.center).padding(.top, 20)
                Text("Cancel now if this was a mistake").foregroundStyle(.white.opacity(0.8)).padding(.top, 8)
                Spacer()
                ZStack {
                    Circle().stroke(.white.opacity(0.1), lineWidth: 12)
                    Circle().trim(from: 0, to: CGFloat(remaining) / 5).stroke(Theme.red, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90)).animation(.linear(duration: 1), value: remaining)
                    Text("\(remaining)").font(.system(size: 104, weight: .heavy).monospacedDigit())
                }
                .frame(width: 220, height: 220)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(remaining) seconds until SOS is sent")
                Spacer()
                Button { timerTask?.cancel(); dismiss() } label: {
                    Text("Cancel").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(.white).foregroundStyle(.black)
                Button("Send now") { send() }
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white.opacity(0.85))
                    .frame(minHeight: 44).padding(.top, 12).padding(.bottom, 24)
            }
            .padding(.horizontal, 24)
        }
        .onAppear {
            Haptics.error()
            timerTask = Task { @MainActor in
                while remaining > 0 && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    if Task.isCancelled { return }
                    remaining -= 1
                    Haptics.tick()
                }
                if !Task.isCancelled { send() }
            }
        }
        .onDisappear { timerTask?.cancel() }
    }

    private func send() {
        timerTask?.cancel()
        Task { await model.sendSOS() }
        dismiss()
    }
}

struct SOSAlertView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @Environment(\.openURL) var openURL
    let sos: SOSEvent
    @State private var responded = false

    var body: some View {
        let live = model.incomingSOS?.id == sos.id ? model.incomingSOS! : sos
        let sender = model.member(live.userId)
        ScrollView {
            VStack(spacing: 14) {
                HStack(spacing: 14) {
                    Avatar(member: sender, name: live.userName, size: 52)
                    VStack(alignment: .leading) {
                        Text(live.isActive ? "SOS · \(live.createdAt, style: .relative) ago".uppercased() : "SOS CLOSED")
                            .font(.footnote.weight(.bold))
                        Text(live.isActive ? "\(live.userName) needs help" : (live.resolution == "false_alarm" ? "False alarm" : "\(live.userName) is safe"))
                            .font(.title2.bold())
                    }
                    Spacer()
                }
                .padding(16)
                .background(live.isActive ? Theme.red : Theme.green.opacity(0.35), in: RoundedRectangle(cornerRadius: 26))

                if let loc = live.location {
                    let coord = CLLocationCoordinate2D(latitude: loc.lat, longitude: loc.lng)
                    Map(coordinateRegion: .constant(MKCoordinateRegion(center: coord, latitudinalMeters: 600, longitudinalMeters: 600)),
                        annotationItems: [Pin(coord: coord)]) { p in
                        MapMarker(coordinate: p.coord, tint: .red)
                    }
                    .frame(height: 250)
                    .clipShape(RoundedRectangle(cornerRadius: 26))
                    .overlay(alignment: .bottom) {
                        HStack {
                            Text("Updated \(loc.at, style: .relative) ago · ±\(Int(loc.accuracy)) m").font(.footnote)
                            Spacer()
                            Button("Directions") {
                                openURL(URL(string: "http://maps.apple.com/?daddr=\(loc.lat),\(loc.lng)")!)
                            }.font(.footnote.weight(.semibold))
                        }
                        .padding(10).glass(16).padding(12)
                    }
                } else {
                    Text("Location not available yet").foregroundStyle(Theme.secondary)
                        .frame(maxWidth: .infinity, minHeight: 120).background(Theme.card, in: RoundedRectangle(cornerRadius: 26))
                }

                HStack(spacing: 10) {
                    Button {
                        if let p = sender?.phone, let u = URL(string: "tel:\(p)") { openURL(u) }
                    } label: {
                        Label("Call \(live.userName)", systemImage: "phone.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).foregroundStyle(Theme.onGreen)
                    .disabled(sender?.phone?.isEmpty ?? true)

                    if let clipId = live.clipIds.first, let clip = model.clips[live.channelId]?.first(where: { $0.id == clipId }) ?? syntheticClip(live, clipId) {
                        Button { Task { await model.play(clip) } } label: {
                            Label("Clip", systemImage: model.playingClipId == clipId ? "pause.fill" : "play.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                        }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule).tint(.white)
                    }
                }

                Button { openURL(URL(string: "tel:112")!) } label: {
                    Text("Call 112").font(.headline).foregroundStyle(Theme.redText).frame(maxWidth: .infinity, minHeight: 56)
                        .overlay(Capsule().strokeBorder(Theme.red, lineWidth: 1.5))
                }

                if sender?.phone?.isEmpty ?? true {
                    Text("\(live.userName) hasn't saved a phone number. Call someone who may be nearby:")
                        .font(.footnote).foregroundStyle(Theme.secondary).frame(maxWidth: .infinity, alignment: .leading)
                }
                CallList(title: "Call family", exclude: model.me?.id, skip: live.userId, firstIds: live.responders)

                if !live.responders.isEmpty {
                    Text("\(live.responders.map { model.name(of: $0) }.joined(separator: " and ")) \(live.responders.count == 1 ? "is" : "are") responding")
                        .font(.subheadline).foregroundStyle(Theme.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 22))
                }

                if live.isActive {
                    Button {
                        responded = true
                        Task { await model.respond(to: live) }
                    } label: {
                        Text(responded || live.responders.contains(model.me?.id ?? "") ? "You're responding" : "I'm responding")
                            .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(.white).foregroundStyle(.black)
                    .padding(.top, 8)
                }
                Button(live.isActive ? "Close (alert stays active)" : "Close") {
                    model.incomingSOS = nil
                    dismiss()
                }
                .foregroundStyle(Theme.secondary).padding(.top, 4)
            }
            .padding(.horizontal, 20).padding(.top, 20)
        }
        .background(Color.black.ignoresSafeArea())
        .task { await model.loadClips(sos.channelId) }
    }

    private func syntheticClip(_ s: SOSEvent, _ id: String) -> Clip? {
        Clip(id: id, channelId: s.channelId, userId: s.userId, userName: s.userName, mode: "sos", sosId: s.id, startedAt: s.createdAt, durationMs: 15000, expiresAt: nil)
    }

    struct Pin: Identifiable { let id = UUID(); let coord: CLLocationCoordinate2D }
}

/// One-tap calls to family members who saved a number (used during an SOS).
struct CallList: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) var openURL
    let title: String
    var exclude: String?
    var skip: String? = nil
    var firstIds: [String] = []

    var body: some View {
        let people = model.everyone
            .filter { $0.id != exclude && $0.id != skip && !($0.phone ?? "").isEmpty }
            .sorted { (firstIds.contains($0.id) ? 0 : 1, $0.name) < (firstIds.contains($1.id) ? 0 : 1, $1.name) }
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionHeader(text: title)
                VStack(spacing: 0) {
                    ForEach(people) { m in
                        Button {
                            if let p = m.phone, let u = URL(string: "tel:\(p)") { openURL(u) }
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(member: m, size: 32)
                                VStack(alignment: .leading) {
                                    Text(m.name).foregroundStyle(.white)
                                    if firstIds.contains(m.id) { Text("Responding").font(.caption).foregroundStyle(Theme.green) }
                                }
                                Spacer()
                                Image(systemName: "phone.fill").foregroundStyle(Theme.green)
                            }
                            .padding(.vertical, 10).padding(.horizontal, 14)
                        }
                        .accessibilityLabel("Call \(m.name)")
                        if m.id != people.last?.id { Divider().padding(.leading, 58) }
                    }
                }
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 22))
            }
        }
    }
}
