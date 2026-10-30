import SwiftUI
import UIKit
import UserNotifications

@main
struct WalkieApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(Theme.green)
                .onOpenURL { url in
                    // walkie://join?s=…&c=…  (from the invite web page or a QR scan)
                    if model.phase == .needsJoin { JoinLinkInbox.shared.link = url.absoluteString }
                }
        }
    }
}

/// Holds an invite link that arrived before the join screen was shown.
final class JoinLinkInbox: ObservableObject {
    static let shared = JoinLinkInbox()
    @Published var link: String?
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Start immediately, also for background launches by a PTT push:
        // PushToTalk needs its channel manager created right away.
        Task { @MainActor in await AppModel.shared.start() }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in AppModel.shared.didRegisterAlertToken(hex) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("APNs registration failed: \(error)")
    }

    // Show SOS banners even while the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let sosId = info["sosId"] as? String else { return }
        await MainActor.run {
            Task {
                let model = AppModel.shared
                await model.loadSOS()
                if response.actionIdentifier == "RESPOND", let s = model.sosHistory.first(where: { $0.id == sosId }) {
                    await model.respond(to: s)
                }
            }
        }
    }
}
