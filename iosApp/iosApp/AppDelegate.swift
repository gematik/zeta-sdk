/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

import UIKit
import UserNotifications
import ZeroTrustShared

/// Wires APNs into the demo app: asks for notification permission, hands the device token to the
/// SDK side, and shows pushes that arrive in the foreground with their decrypted text.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { application.registerForRemoteNotifications() }
        }
        return true
    }

    // The pushkey is the device token as 64 lowercase hex characters.
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        IosPushBridge.shared.onApnsToken(hexToken: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    // Expected without a paid Apple team (no push entitlement); APNS_MOCK=true then supplies a fake token.
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        IosPushBridge.shared.onApnsRegistrationFailed(message: error.localizedDescription)
    }

    // App in the foreground: decrypt in-process and show the real text instead of the gateway's
    // "title / subtitle / body" placeholder. Pushes in the background need a Notification Service Extension.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let info = notification.request.content.userInfo
        guard let ciphertext = info["ciphertext"] as? String,
              let monthTag = info["time_message_encrypted"] as? String else {
            return [.banner, .list, .sound]
        }
        if let text = try? await IosPushBridge.shared.decryptForDisplay(ciphertext: ciphertext, timeMessageEncrypted: monthTag) {
            let content = UNMutableNotificationContent()
            content.title = "ZETA Notification"
            content.body = text
            content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        }
        return []
    }
}
