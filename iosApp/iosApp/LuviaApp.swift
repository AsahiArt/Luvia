import SwiftUI
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    weak var model: AppModel?
    private var pendingUserInfo: [AnyHashable: Any]?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        TerminalFont.register()
        UNUserNotificationCenter.current().delegate = self
        if let userInfo = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            pendingUserInfo = userInfo
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let model = model
        _Concurrency.Task { @MainActor in
            model?.didRegisterPushToken(deviceToken)
        }
    }

    func bind(_ model: AppModel) {
        self.model = model
        flushPending()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        handle(response.notification.request.content.userInfo)
        completionHandler()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    private func handle(_ userInfo: [AnyHashable: Any]) {
        if let model {
            _Concurrency.Task { @MainActor in
                model.handlePushUserInfo(userInfo)
            }
        } else {
            pendingUserInfo = userInfo
        }
    }

    private func flushPending() {
        guard let model, let pendingUserInfo else { return }
        let info = pendingUserInfo
        self.pendingUserInfo = nil
        _Concurrency.Task { @MainActor in
            model.handlePushUserInfo(info)
        }
    }

}

@main
struct LuviaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    init() {
        TerminalFont.register()
    }
    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .tint(DesignTokens.accent)
                .onAppear { appDelegate.bind(model) }
        }
    }
}
