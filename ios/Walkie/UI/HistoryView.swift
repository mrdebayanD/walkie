import SwiftUI

/// History is per channel: pick a channel (or personal line) at the top;
/// each one shows how many messages you haven't heard.
struct HistoryView: View {
    @EnvironmentObject var model: AppModel
    @State private var channelId: String?
    @State private var filter = Filter.all
    enum Filter: String, CaseIterable { case all = "All", missed = "Missed", sos = "SOS" }

    var body: some View {
        NavigationStack {
            let ch = model.channels.first { $0.id == channelId } ?? model.selectedChannel
            let all = ch.flatMap { model.clips[$0.id] } ?? []
            let missed = all.filter { !model.playedClips.contains($0.id) && $0.userId != model.me?.id }
            let shown: [Clip] = {
                switch filter {
                case .all: return all
                case .missed: return missed
                case .sos: return all.filter { $0.mode == "sos" }
                }
            }()
            List {
                Section {
                    channelPicker(selected: ch?.id)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                    Picker("Filter", selection: $filter) {
                        ForEach(Filter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                } footer: {
                    if let ch, ch.isPersonal {
                        Label("Only you and \(model.title(ch)) can hear these.", systemImage: "lock.fill").font(.footnote)
                    } else {
                        Text("Clips delete themselves after \(model.settings.clipTtlHours) hours").font(.footnote)
                    }
                }
                if shown.isEmpty {
                    Section {
                        Text(filter == .missed ? "You're all caught up." : "No messages in the last \(model.settings.clipTtlHours) hours.")
                            .foregroundStyle(Theme.secondary)
                    }
                }
                ForEach(groups(shown), id: \.0) { title, items in
                    Section(title) {
                        ForEach(items) { clip in ClipRow(clip: clip) }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .navigationTitle("History")
            .toolbar {
                if !missed.isEmpty, let ch {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button { Task { await model.playMissed(ch.id) } } label: {
                            Label("Play \(missed.count) missed", systemImage: "play.fill").labelStyle(.titleAndIcon)
                        }
                        .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                        .foregroundStyle(Theme.onGreen)
                    }
                }
            }
            .refreshable { await model.loadAllClips() }
            .task { await model.loadAllClips() }
            .onAppear { if channelId == nil { channelId = model.selectedChannelId } }
        }
    }

    private func channelPicker(selected: String?) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.groupChannels + model.personalChannels) { c in
                    let on = c.id == selected
                    let n = model.unheard(in: c.id)
                    Button { channelId = c.id } label: {
                        HStack(spacing: 6) {
                            if c.isPersonal { Image(systemName: "lock.fill").font(.caption2) }
                            Text(model.title(c)).font(.subheadline.weight(.semibold))
                            if n > 0 {
                                Text("\(n)").font(.caption2.weight(.bold)).foregroundStyle(.white)
                                    .padding(.horizontal, 6).frame(minWidth: 18, minHeight: 18)
                                    .background(Color.blue, in: Capsule())
                            }
                        }
                        .padding(.horizontal, 14).frame(height: 36)
                        .foregroundStyle(on ? Theme.onGreen : .white)
                        .background(on ? AnyShapeStyle(Theme.green) : AnyShapeStyle(Theme.glassFill), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(model.title(c))\(c.isPersonal ? ", personal" : "")\(n > 0 ? ", \(n) unheard" : "")")
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func groups(_ clips: [Clip]) -> [(String, [Clip])] {
        let cal = Calendar.current
        var today: [Clip] = [], yesterday: [Clip] = [], older: [Clip] = []
        for c in clips {
            if cal.isDateInToday(c.startedAt) { today.append(c) } else if cal.isDateInYesterday(c.startedAt) { yesterday.append(c) } else { older.append(c) }
        }
        return [("Today", today), ("Yesterday", yesterday), ("Earlier", older)].filter { !$0.1.isEmpty }
    }
}

struct ClipRow: View {
    @EnvironmentObject var model: AppModel
    let clip: Clip

    var body: some View {
        let playing = model.playingClipId == clip.id
        let unheard = !model.playedClips.contains(clip.id) && clip.userId != model.me?.id
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Avatar(member: model.member(clip.userId), name: clip.userName, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(clip.userId == model.me?.id ? "You" : clip.userName).font(.body.weight(.semibold))
                        if clip.mode == "sos" { Text("SOS").font(.caption2.weight(.bold)).foregroundStyle(Theme.redText) }
                        if unheard {
                            Circle().fill(Color.blue).frame(width: 8, height: 8).accessibilityLabel("Not played")
                        }
                    }
                    Text(meta).font(.footnote).foregroundStyle(expiringSoon ? Theme.orange : Theme.secondary)
                }
                Spacer()
                Button { Task { await model.play(clip) } } label: {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.footnote)
                        .frame(width: 40, height: 40)
                        .foregroundStyle(playing ? Theme.onGreen : .white)
                        .background(playing ? AnyShapeStyle(Theme.green) : AnyShapeStyle(Theme.glassFill), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playing ? "Pause" : "Play \(clip.userName)'s message")
            }
            if playing {
                ProgressView(value: model.playProgress).tint(Theme.green).padding(.leading, 52)
            }
        }
        .listRowBackground(playing ? Theme.green.opacity(0.10) : Theme.card)
    }

    private var expiringSoon: Bool {
        guard let e = clip.expiresAt else { return false }
        return e.timeIntervalSinceNow < 3 * 3600
    }

    private var meta: String {
        let t = clip.startedAt.formatted(date: .omitted, time: .shortened)
        var s = "\(t) · \(Durations.short(clip.durationMs))"
        if expiringSoon, let e = clip.expiresAt {
            let h = max(1, Int(e.timeIntervalSinceNow / 3600))
            s += " · Deletes in \(h) h"
        }
        return s
    }
}
