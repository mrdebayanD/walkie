import SwiftUI

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch model.phase {
            case .loading:
                VStack(spacing: 16) {
                    ProgressView().tint(.white)
                    Text(model.errorMessage ?? "Connecting…").font(.subheadline).foregroundStyle(Theme.secondary)
                }
            case .needsJoin:
                JoinView()
            case .pending:
                PendingView()
            case .ready:
                MainTabs()
            }
        }
        .fullScreenCover(item: $model.incomingSOS) { sos in
            SOSAlertView(sos: sos)
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView {
            TalkView()
                .tabItem { Label("Talk", systemImage: "mic.fill") }
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
            FamilyView()
                .tabItem { Label("Family", systemImage: "person.2.fill") }
                .badge(model.pendingUsers.count)
            SafetyView()
                .tabItem { Label("Safety", systemImage: "shield.fill") }
        }
        .overlay(alignment: .top) { ErrorToast() }
    }
}

struct ErrorToast: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        if let msg = model.errorMessage, model.phase == .ready {
            Text(msg)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 16).padding(.vertical, 12)
                .glass(20)
                .padding(.top, 8).padding(.horizontal, 20)
                .transition(.move(edge: .top).combined(with: .opacity))
                .onTapGesture { model.errorMessage = nil }
                .task(id: msg) {
                    try? await Task.sleep(nanoseconds: 3_500_000_000)
                    if model.errorMessage == msg { withAnimation { model.errorMessage = nil } }
                }
                .accessibilityAddTraits(.isStaticText)
        }
    }
}

// MARK: - Join

struct JoinView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject private var inbox = JoinLinkInbox.shared
    @State private var link = ""
    @State private var code = ""
    @State private var name = ""
    @State private var busy = false
    @FocusState private var focus: Field?
    enum Field { case link, code, name }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 44, weight: .semibold)).foregroundStyle(Theme.green)
                        .padding(.bottom, 8)
                    Text("Join your family").font(.largeTitle.bold())
                    Text("Open the invite link you were sent, or paste it here.").foregroundStyle(Theme.secondary)
                }
                .padding(.top, 40)

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(text: "Invite link")
                    TextField("https://…/join?c=…", text: $link)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .keyboardType(.URL).focused($focus, equals: .link)
                        .padding(14).background(Theme.card2, in: RoundedRectangle(cornerRadius: 14))
                    if !link.isEmpty, AppModel.parseInvite(link: link, code: nil) == nil {
                        TextField("Invite code", text: $code)
                            .textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .focused($focus, equals: .code)
                            .padding(14).background(Theme.card2, in: RoundedRectangle(cornerRadius: 14))
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(text: "Your name")
                    TextField("e.g. Ma, Baba, Didi", text: $name)
                        .textContentType(.givenName).focused($focus, equals: .name)
                        .padding(14).background(Theme.card2, in: RoundedRectangle(cornerRadius: 14))
                    Text("This is how the family sees you.").font(.footnote).foregroundStyle(Theme.secondary).padding(.leading, 16)
                }

                if let e = model.errorMessage {
                    Text(e).font(.subheadline).foregroundStyle(Theme.redText)
                }

                Button {
                    busy = true
                    Task {
                        await model.join(link: link, code: code, name: name)
                        busy = false
                    }
                } label: {
                    HStack { if busy { ProgressView().tint(Theme.onGreen) }; Text("Join").font(.headline) }
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                .foregroundStyle(Theme.onGreen)
                .disabled(busy || link.isEmpty || name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 20)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { if let l = inbox.link { link = l; focus = .name } }
        .onChange(of: inbox.link) { l in if let l { link = l; focus = .name } }
    }
}

struct PendingView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "hourglass").font(.system(size: 48)).foregroundStyle(Theme.orange)
            Text("Waiting for approval").font(.title.bold())
            Text("Hi \(model.me?.name ?? ""). The family admin needs to approve you. This screen updates by itself.")
                .multilineTextAlignment(.center).foregroundStyle(Theme.secondary)
            ProgressView().tint(.white).padding(.top, 8)
            Spacer()
            Button("Use a different invite", role: .destructive) { model.signOut() }
                .padding(.bottom, 24)
        }
        .padding(.horizontal, 32)
    }
}
