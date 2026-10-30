import CoreImage.CIFilterBuiltins
import SwiftUI

struct InviteView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @Environment(\.openURL) var openURL
    @State var channelId: String?
    @State private var label = ""
    @State private var invite: InviteResult?
    @State private var phone = ""
    @State private var busy = false
    @State private var open: [PendingInvite] = []

    init(channelId: String?) { _channelId = State(initialValue: channelId) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Channel", selection: $channelId) {
                        ForEach(model.channels) { Text($0.name).tag(Optional($0.id)) }
                    }
                    .onChange(of: channelId) { _ in invite = nil }
                    TextField("Who is it for? (e.g. Pishi)", text: $label)
                        .onChange(of: label) { _ in invite = nil }
                }

                if let invite {
                    Section {
                        VStack(spacing: 12) {
                            QRCode(text: invite.url)
                                .frame(width: 196, height: 196)
                                .padding(18)
                                .background(.white, in: RoundedRectangle(cornerRadius: 28))
                                .accessibilityLabel("QR code for the invite link")
                            Text("Scan with the new phone's camera").font(.subheadline).foregroundStyle(Theme.secondary)
                            Text("Code: \(invite.code)").font(.system(.body, design: .monospaced))
                        }
                        .frame(maxWidth: .infinity)
                        .listRowBackground(Color.clear)
                        ShareLink(item: URL(string: invite.url)!, message: Text("Join our family walkie-talkie: \(invite.url)")) {
                            Label("Share invite link", systemImage: "square.and.arrow.up")
                        }
                    }
                    Section {
                        HStack {
                            Text("+91").foregroundStyle(Theme.secondary)
                            TextField("Mobile number", text: $phone).keyboardType(.phonePad)
                            Button("Send") { sendSMS(invite) }.disabled(phone.filter(\.isNumber).count < 6)
                        }
                    } header: { Text("Or send by text message") }
                    footer: { Text("Links expire in 24 hours and work once. Nobody joins until you approve them.") }
                } else {
                    Section {
                        Button {
                            Task { await create() }
                        } label: {
                            HStack { if busy { ProgressView() }; Text("Create invite") }.frame(maxWidth: .infinity)
                        }
                        .disabled(busy || channelId == nil)
                    }
                }

                if !open.isEmpty {
                    Section("Open invites") {
                        ForEach(open) { i in
                            VStack(alignment: .leading) {
                                Text(i.label.isEmpty ? "Unnamed invite" : i.label)
                                Text("\(model.channels.first { $0.id == i.channelId }?.name ?? "") · expires \(i.expiresAt, style: .relative)")
                                    .font(.footnote).foregroundStyle(Theme.secondary)
                            }
                            .swipeActions {
                                Button("Delete", role: .destructive) { Task { try? await model.api?.deleteInvite(i.id); await loadOpen() } }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Add People")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { await loadOpen() }
        }
    }

    private func create() async {
        guard let channelId, let api = model.api else { return }
        busy = true
        defer { busy = false }
        do {
            invite = try await api.createInvite(channelId: channelId, label: label)
            await loadOpen()
        } catch { model.errorMessage = error.localizedDescription }
    }

    private func loadOpen() async {
        open = (try? await model.api?.invites()) ?? []
    }

    private func sendSMS(_ invite: InviteResult) {
        var digits = phone.filter { $0.isNumber || $0 == "+" }
        if !digits.hasPrefix("+") { digits = "+91" + digits }
        let body = "Join our family walkie-talkie: \(invite.url)"
        var c = URLComponents()
        c.scheme = "sms"
        c.path = digits
        c.queryItems = [URLQueryItem(name: "body", value: body)]
        if let u = c.url { openURL(u) }
    }
}

struct QRCode: View {
    let text: String
    var body: some View {
        if let img = QRCode.make(text) {
            Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
        } else {
            Text(text).font(.caption)
        }
    }

    static func make(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
