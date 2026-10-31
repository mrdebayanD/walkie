import SwiftUI

struct FamilyView: View {
    @EnvironmentObject var model: AppModel
    @State private var showNewChannel = false
    @State private var showProfile = false

    var body: some View {
        NavigationStack {
            List {
                if model.isAdmin && !model.pendingUsers.isEmpty {
                    Section("Waiting for you") {
                        ForEach(model.pendingUsers) { u in
                            HStack(spacing: 12) {
                                Avatar(member: u, size: 36)
                                VStack(alignment: .leading) {
                                    Text(u.name)
                                    Text("Joined with link · \(u.platform == "ios" ? "iPhone" : "Android")").font(.footnote).foregroundStyle(Theme.secondary)
                                }
                                Spacer()
                                Button("Approve") { Task { try? await model.api?.approve(u.id); await model.loadPending(); await model.loadChannels() } }
                                    .buttonStyle(.bordered).buttonBorderShape(.capsule).tint(Theme.green)
                            }
                            .swipeActions {
                                Button("Reject", role: .destructive) { Task { try? await model.api?.removeUser(u.id); await model.loadPending() } }
                            }
                        }
                    }
                }

                Section {
                    ForEach(model.groupChannels) { c in
                        NavigationLink(value: c.id) { ChannelRow(channel: c) }
                    }
                    if model.isAdmin {
                        Button { showNewChannel = true } label: { Label("New channel", systemImage: "plus") }
                    }
                } header: { Text("Channels") } footer: {
                    if model.isAdmin { Text("Open a channel to add people to it.") }
                }

                if !model.personalChannels.isEmpty {
                    Section {
                        ForEach(model.personalChannels) { c in
                            Button {
                                model.selectedChannelId = c.id
                                model.selectedTab = 0
                            } label: { ChannelRow(channel: c) }
                            .swipeActions {
                                Button("Delete", role: .destructive) { Task { await model.deletePersonal(c) } }
                            }
                        }
                    } header: { Text("Personal") } footer: {
                        Text("Only the two of you can hear or replay these. Swipe to delete one and its history.")
                    }
                }

                Section("This phone") {
                    Toggle(isOn: $model.onDuty) {
                        VStack(alignment: .leading) {
                            Text("On duty")
                            Text("Hear the family live, even when the phone is locked").font(.footnote).foregroundStyle(Theme.secondary)
                        }
                    }
                    Toggle(isOn: $model.speakerMuted) {
                        VStack(alignment: .leading) {
                            Text("Mute speaker")
                            Text("Vibrate only, when phones share a room").font(.footnote).foregroundStyle(Theme.secondary)
                        }
                    }
                    if model.isAdmin {
                        Picker("Delete clips after", selection: Binding(get: { model.settings.clipTtlHours }, set: { h in
                            Task { if let s = try? await model.api?.setClipTTL(hours: h) { model.settings = s } }
                        })) {
                            ForEach([1, 6, 12, 24, 48, 72, 168], id: \.self) { h in Text(h == 168 ? "7 days" : "\(h) hours").tag(h) }
                        }
                    } else {
                        LabeledContent("Clips delete after", value: "\(model.settings.clipTtlHours) hours")
                    }
                    Button { showProfile = true } label: {
                        LabeledContent("My name and phone", value: model.me?.phone?.isEmpty == false ? model.me!.phone! : "Add phone")
                    }
                }

                Section {
                    Button("Leave this family", role: .destructive) { model.signOut() }
                } footer: {
                    Text("Connected to \(model.serverURL?.host ?? "—")").font(.footnote)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Family")
            .navigationDestination(for: String.self) { id in ChannelDetail(channelId: id) }
            .refreshable { await model.loadChannels(); await model.loadPending() }
            .sheet(isPresented: $showNewChannel) { ChannelEditor(channel: nil) }
            .sheet(isPresented: $showProfile) { ProfileView(member: nil) }
        }
    }
}

struct ChannelRow: View {
    @EnvironmentObject var model: AppModel
    let channel: Channel
    var body: some View {
        let c = channel
        HStack(spacing: 12) {
            if c.isPersonal {
                Avatar(member: c.other(me: model.me?.id), size: 32)
            } else {
                RoundedRectangle(cornerRadius: 9).fill(Theme.channelColor(c.isDefault ? "green" : c.color))
                    .frame(width: 32, height: 32)
                    .overlay(Image(systemName: c.isDefault ? "house.fill" : "number").font(.footnote.weight(.bold)).foregroundStyle(c.isDefault ? Theme.onGreen : .white))
            }
            VStack(alignment: .leading) {
                HStack(spacing: 4) {
                    if c.isPersonal { Image(systemName: "lock.fill").font(.caption2).foregroundStyle(Theme.secondary) }
                    Text(model.title(c)).font(.body.weight(.semibold)).foregroundStyle(.white)
                }
                Text(c.isPersonal ? "Personal" : (c.isDefault ? "Pinned · \(c.members.count) members" : "\(c.members.count) members"))
                    .font(.footnote).foregroundStyle(Theme.secondary)
            }
            Spacer()
            if c.isDefault { Text("Default").font(.footnote.weight(.semibold)).foregroundStyle(Theme.green) }
            if model.selectedChannelId == c.id { Image(systemName: "checkmark").foregroundStyle(Theme.green) }
        }
    }
}

/// One channel: who's in it, call or talk privately to anyone, and (admin) add people.
struct ChannelDetail: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) var openURL
    let channelId: String
    @State private var showInvite = false
    @State private var showEdit = false
    @State private var editingMember: Member?

    var body: some View {
        if let c = model.channels.first(where: { $0.id == channelId }) {
            List {
                Section {
                    Button {
                        model.selectedChannelId = c.id
                        model.selectedTab = 0
                    } label: { Label("Talk in \(c.name)", systemImage: "mic.fill") }
                    Button {
                        model.selectedTab = 1
                    } label: { Label("History of \(c.name)", systemImage: "clock.arrow.circlepath") }
                }

                Section("Members · \(c.members.count)") {
                    ForEach(c.members) { m in
                        HStack(spacing: 12) {
                            Avatar(member: m, size: 36, showStatus: true)
                            VStack(alignment: .leading) {
                                Text(m.id == model.me?.id ? "\(m.name) (you)" : m.name)
                                Text([m.platform == "ios" ? "iPhone" : "Android", m.isAdmin ? "Admin" : nil, m.phone].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote).foregroundStyle(Theme.secondary)
                            }
                            Spacer()
                            if m.id != model.me?.id {
                                if let p = m.phone, !p.isEmpty, let u = URL(string: "tel:\(p)") {
                                    Button { openURL(u) } label: { Image(systemName: "phone.fill") }
                                        .buttonStyle(.borderless).foregroundStyle(Theme.green)
                                        .frame(width: 44, height: 44)
                                        .accessibilityLabel("Call \(m.name)")
                                }
                                Button { Task { await model.talkPrivately(with: m) } } label: { Image(systemName: "lock.fill") }
                                    .buttonStyle(.borderless).foregroundStyle(.white)
                                    .frame(width: 44, height: 44)
                                    .accessibilityLabel("Talk to \(m.name) only")
                            }
                        }
                        .swipeActions {
                            if model.isAdmin && m.id != model.me?.id {
                                Button("Remove", role: .destructive) { Task { try? await model.api?.removeUser(m.id); await model.loadChannels() } }
                                Button("Edit") { editingMember = m }.tint(.gray)
                            }
                        }
                    }
                    if model.isAdmin {
                        Button { showInvite = true } label: { Label("Add people to \(c.name)", systemImage: "person.badge.plus") }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(c.name)
            .toolbar {
                if model.isAdmin {
                    ToolbarItem(placement: .navigationBarTrailing) { Button("Edit") { showEdit = true } }
                }
            }
            .sheet(isPresented: $showInvite) { InviteView(channel: c) }
            .sheet(isPresented: $showEdit) { ChannelEditor(channel: c) }
            .sheet(item: $editingMember) { ProfileView(member: $0) }
        } else {
            Text("This channel was removed.").foregroundStyle(Theme.secondary)
        }
    }
}

struct ChannelEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    let channel: Channel?
    @State private var name = ""
    @State private var color = "blue"
    @State private var selected: Set<String> = []
    @State private var busy = false
    private let colors = ["blue", "green", "orange", "pink", "purple", "teal"]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 14) {
                        RoundedRectangle(cornerRadius: 24).fill(Theme.channelColor(channel?.isDefault == true ? "green" : color))
                            .frame(width: 84, height: 84)
                            .overlay(Image(systemName: channel?.isDefault == true ? "house.fill" : "number").font(.system(size: 34, weight: .bold)))
                        if channel?.isDefault != true {
                            HStack(spacing: 12) {
                                ForEach(colors, id: \.self) { c in
                                    Button { color = c } label: {
                                        Circle().fill(Theme.channelColor(c)).frame(width: 30, height: 30)
                                            .overlay(Circle().strokeBorder(.white, lineWidth: color == c ? 2 : 0))
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(c.capitalized)
                                    .accessibilityAddTraits(color == c ? .isSelected : [])
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                Section("Name") { TextField("Channel name", text: $name) }
                Section {
                    ForEach(model.everyone) { m in
                        Button {
                            if m.id == model.me?.id { return }
                            if selected.contains(m.id) { selected.remove(m.id) } else { selected.insert(m.id) }
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(member: m, size: 34)
                                Text(m.id == model.me?.id ? "\(m.name) (you)" : m.name).foregroundStyle(.white)
                                Spacer()
                                Image(systemName: selected.contains(m.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected.contains(m.id) ? Theme.green : Theme.secondary)
                            }
                        }
                        .accessibilityAddTraits(selected.contains(m.id) ? .isSelected : [])
                    }
                } header: { Text("Members · \(selected.count) selected") } footer: {
                    Text(channel == nil ? "To bring in someone new, create the channel, open it, and tap Add people." : "To bring in someone new, use Add people on the channel page.")
                }
                if let channel, !channel.isDefault {
                    Section {
                        Button("Delete channel", role: .destructive) {
                            Task { try? await model.api?.deleteChannel(channel.id); await model.loadChannels(); dismiss() }
                        }
                    }
                }
            }
            .navigationTitle(channel == nil ? "New Channel" : "Edit \(channel!.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(channel == nil ? "Create" : "Save") { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                if let channel {
                    name = channel.name
                    color = channel.color
                    selected = Set(channel.members.map(\.id))
                } else if let me = model.me {
                    selected = [me.id]
                }
            }
        }
    }

    private func save() async {
        guard let api = model.api else { return }
        busy = true
        defer { busy = false }
        do {
            if let channel {
                try await api.updateChannel(channel.id, name: name, color: channel.isDefault ? nil : color)
                try await api.setMembers(channel.id, memberIds: Array(selected))
            } else {
                _ = try await api.createChannel(name: name, color: color, memberIds: Array(selected))
            }
            await model.loadChannels()
            dismiss()
        } catch {
            model.errorMessage = error.localizedDescription
        }
    }
}

/// Edit your own name/phone, or (admin) someone else's.
struct ProfileView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    let member: Member?
    @State private var name = ""
    @State private var phone = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") { TextField("Name", text: $name) }
                Section {
                    TextField("+91 98765 43210", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber)
                } header: { Text("Phone number") } footer: {
                    Text("The family can call this number from the member list and from an SOS alert.")
                }
            }
            .navigationTitle(member == nil ? "You" : member!.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            do {
                                if let member {
                                    try await model.api?.updateUser(member.id, name: name, phone: phone)
                                } else {
                                    model.me = try await model.api?.updateMe(name: name, phone: phone)
                                }
                                await model.loadChannels()
                                dismiss()
                            } catch { model.errorMessage = error.localizedDescription }
                        }
                    }
                }
            }
            .onAppear {
                let m = member ?? model.me
                name = m?.name ?? ""
                phone = m?.phone ?? ""
            }
        }
    }
}
