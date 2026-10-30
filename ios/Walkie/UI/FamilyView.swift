import SwiftUI

struct FamilyView: View {
    @EnvironmentObject var model: AppModel
    @State private var showNewChannel = false
    @State private var showInvite = false
    @State private var editing: Channel?
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

                Section("Channels") {
                    ForEach(model.channels) { c in
                        Button { if model.isAdmin { editing = c } else { model.selectedChannelId = c.id } } label: {
                            HStack(spacing: 12) {
                                RoundedRectangle(cornerRadius: 9).fill(Theme.channelColor(c.isDefault ? "green" : c.color))
                                    .frame(width: 32, height: 32)
                                    .overlay(Image(systemName: c.isDefault ? "house.fill" : "number").font(.footnote.weight(.bold)).foregroundStyle(c.isDefault ? Theme.onGreen : .white))
                                VStack(alignment: .leading) {
                                    Text(c.name).font(.body.weight(.semibold)).foregroundStyle(.white)
                                    Text(c.isDefault ? "Pinned · \(c.members.count) members" : "\(c.members.count) members")
                                        .font(.footnote).foregroundStyle(Theme.secondary)
                                }
                                Spacer()
                                if c.isDefault { Text("Default").font(.footnote.weight(.semibold)).foregroundStyle(Theme.green) }
                                if model.selectedChannelId == c.id { Image(systemName: "checkmark").foregroundStyle(Theme.green) }
                            }
                        }
                    }
                    if model.isAdmin {
                        Button { showNewChannel = true } label: { Label("New channel", systemImage: "plus") }
                    }
                }

                Section("Members") {
                    ForEach(allMembers) { m in
                        HStack(spacing: 12) {
                            Avatar(member: m, size: 36)
                            VStack(alignment: .leading) {
                                Text(m.id == model.me?.id ? "\(m.name) (you)" : m.name)
                                Text("\(m.platform == "ios" ? "iPhone" : "Android")\(m.isAdmin ? " · Admin" : "")")
                                    .font(.footnote).foregroundStyle(Theme.secondary)
                            }
                            Spacer()
                            HStack(spacing: 6) {
                                Circle().fill(m.duty ? Theme.green : Theme.secondary).frame(width: 8, height: 8)
                                Text(m.duty ? "On duty" : (m.online ? "Online" : "Off")).font(.footnote)
                            }
                            .foregroundStyle(m.duty ? Theme.green : Theme.secondary)
                        }
                        .swipeActions {
                            if model.isAdmin && m.id != model.me?.id {
                                Button("Remove", role: .destructive) { Task { try? await model.api?.removeUser(m.id); await model.loadChannels() } }
                            }
                        }
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
                    Button("My name and phone number") { showProfile = true }
                }

                Section {
                    Button("Leave this family", role: .destructive) { model.signOut() }
                } footer: {
                    Text("Connected to \(model.serverURL?.host ?? "—")").font(.footnote)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Family")
            .toolbar {
                if model.isAdmin {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button { showInvite = true } label: { Label("Add people", systemImage: "person.badge.plus").labelStyle(.titleAndIcon) }
                            .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).foregroundStyle(Theme.onGreen)
                    }
                }
            }
            .refreshable { await model.loadChannels(); await model.loadPending() }
            .sheet(isPresented: $showNewChannel) { ChannelEditor(channel: nil) }
            .sheet(item: $editing) { ChannelEditor(channel: $0) }
            .sheet(isPresented: $showInvite) { InviteView(channelId: model.defaultChannel?.id) }
            .sheet(isPresented: $showProfile) { ProfileView() }
        }
    }

    private var allMembers: [Member] {
        var seen = Set<String>()
        var out: [Member] = []
        for c in model.channels { for m in c.members where !seen.contains(m.id) { seen.insert(m.id); out.append(m) } }
        return out.sorted { $0.name < $1.name }
    }
}

struct ChannelEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    let channel: Channel?
    @State private var name = ""
    @State private var color = "blue"
    @State private var selected: Set<String> = []
    @State private var showInvite = false
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
                Section("Name") {
                    TextField("Channel name", text: $name)
                }
                Section("Members · \(selected.count) selected") {
                    ForEach(people) { m in
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
                    Button { showInvite = true } label: { Label("Invite someone new", systemImage: "person.badge.plus") }
                        .disabled(channel == nil)
                }
                Section {
                    EmptyView()
                } footer: {
                    Text(channel == nil ? "Create the channel first, then invite new people to it. Only you can add or remove people. Family stays your default channel."
                                        : "Only you can add or remove people.")
                }
                if let channel, !channel.isDefault {
                    Section {
                        Button("Delete channel", role: .destructive) {
                            Task { try? await model.api?.deleteChannel(channel.id); await model.loadChannels(); dismiss() }
                        }
                    }
                }
            }
            .navigationTitle(channel == nil ? "New Channel" : channel!.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(channel == nil ? "Create" : "Save") { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .sheet(isPresented: $showInvite) { InviteView(channelId: channel?.id) }
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

    private var people: [Member] {
        var seen = Set<String>(), out: [Member] = []
        for c in model.channels { for m in c.members where !seen.contains(m.id) { seen.insert(m.id); out.append(m) } }
        return out.sorted { $0.name < $1.name }
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

struct ProfileView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var name = ""
    @State private var phone = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") { TextField("Name", text: $name) }
                Section {
                    TextField("+91 98765 43210", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber)
                } header: { Text("Phone number") } footer: {
                    Text("Shown to your family so they can call you from an SOS alert.")
                }
            }
            .navigationTitle("You")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            do {
                                model.me = try await model.api?.updateMe(name: name, phone: phone)
                                await model.loadChannels()
                                dismiss()
                            } catch { model.errorMessage = error.localizedDescription }
                        }
                    }
                }
            }
            .onAppear { name = model.me?.name ?? ""; phone = model.me?.phone ?? "" }
        }
    }
}
