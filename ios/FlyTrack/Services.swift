import FlyTrackCore
import Foundation
import PDFKit
import Supabase
import SwiftUI
import UIKit
import UserNotifications
import Vision

#if canImport(FirebaseCore)
import FirebaseCore
import FirebaseMessaging
#endif
#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

@MainActor
final class DeviceSettings: SettingsStore, DefaultAlertPreferences {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func cloudTicketParsingConsent() async -> Bool { defaults.bool(forKey: "cloud_ticket_consent") }
    func setCloudTicketParsingConsent(_ granted: Bool) async { defaults.set(granted, forKey: "cloud_ticket_consent") }
    func notificationPermissionAsked() async -> Bool { defaults.bool(forKey: "notification_permission_asked") }
    func setNotificationPermissionAsked(_ asked: Bool) async { defaults.set(asked, forKey: "notification_permission_asked") }

    func muted() async -> Set<AlertType> {
        let raw = defaults.stringArray(forKey: "default_muted_alerts") ?? []
        return Set(raw.compactMap(AlertType.fromWire))
    }

    func setMuted(_ types: Set<AlertType>, muted: Bool) async {
        var current = await self.muted()
        if muted { current.formUnion(types) } else { current.subtract(types) }
        defaults.set(current.map(\.wire), forKey: "default_muted_alerts")
    }
}

@MainActor
final class LiveHealth: NotificationHealthSource {
    func read() async -> NotificationHealth {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let authorization: NotificationHealth.Authorization
        switch settings.authorizationStatus {
        case .notDetermined: authorization = .notDetermined
        case .denied: authorization = .denied
        case .provisional, .ephemeral: authorization = .provisional
        default: authorization = .authorized
        }
        let alerts = settings.alertSetting == .enabled
        let timeSensitive = settings.timeSensitiveSetting == .enabled
        return NotificationHealth(authorization: authorization, timeSensitiveAllowed: timeSensitive, alertsAllowed: alerts)
    }
}

@MainActor
final class LivePush: PushControlling {
    private let flights: FlightRepository
    init(flights: FlightRepository) { self.flights = flights }

    func availability() async -> PushAvailability {
        AppConfig.pushPlistPresent ? .ready : .notConfigured
    }

    func beforeSignOut() async {
        guard AppConfig.pushPlistPresent, let token = await currentToken() else { return }
        PushBridge.shared.pendingUnregister = token
    }

    func start() {
        guard AppConfig.pushPlistPresent else { return }
        #if canImport(FirebaseCore) && canImport(FirebaseMessaging)
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        UNUserNotificationCenter.current().delegate = PushBridge.shared
        UIApplication.shared.registerForRemoteNotifications()
        Messaging.messaging().delegate = PushBridge.shared
        #endif
    }

    func currentToken() async -> String? {
        guard AppConfig.pushPlistPresent, FirebaseApp.app() != nil else { return nil }
        #if canImport(FirebaseMessaging)
        // Firebase 13 deprecates token(completion:) in favour of installation-id registration.
        // Installation-id mode is off unless FirebaseMessagingInstallationIdEnabled is set, and
        // the backend still addresses FCM registration tokens, so this is the call that returns one.
        return await withCheckedContinuation { continuation in
            Messaging.messaging().token { token, _ in continuation.resume(returning: token) }
        }
        #else
        return nil
        #endif
    }

    func handle(data: [String: String]) async {
        switch parseIncomingPush(data) {
        case .sync(let flightId, let json):
            _ = await flights.applyServerSync(flightId: flightId, compactFlightJSON: json)
        case .alert(let alert):
            await post(alert)
            _ = await flights.applyServerSync(flightId: alert.flightId, compactFlightJSON: nil)
        case .ignored:
            break
        }
    }

    private func post(_ alert: PushAlert) async {
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.threadIdentifier = alert.tag
        content.categoryIdentifier = PushContract.actionOpenFlight
        content.userInfo = [PushContract.keyFlightId: alert.flightId, PushContract.keyKind: PushContract.kindAlert]
        content.interruptionLevel = alert.interruptionLevel == "time-sensitive" ? .timeSensitive : .active
        let request = UNNotificationRequest(identifier: alert.tag, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}

/// Holds the APNs/FCM callbacks. Firebase is never messaged unless GoogleService-Info.plist is in the bundle.
/// Delegate methods stay nonisolated; they hop to the main actor before touching the callbacks.
@MainActor
final class PushBridge: NSObject, UNUserNotificationCenterDelegate {
    static let shared = PushBridge()
    var onData: (([String: String]) -> Void)?
    var onFlight: ((String) -> Void)?
    var onToken: ((String) -> Void)?
    var pendingUnregister: String?

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let id = info[PushContract.keyFlightId] as? String, isFlightUUID(id) else { return }
        await MainActor.run { PushBridge.shared.onFlight?(id) }
    }
}

#if canImport(FirebaseMessaging)
extension PushBridge: MessagingDelegate {
    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken else { return }
        Task { @MainActor in PushBridge.shared.onToken?(fcmToken) }
    }
}
#endif

enum DeviceRegistrar {
    @MainActor
    static func register(token: String, client: SupabaseClient?) async {
        guard let client else { return }
        let params = RegisterParams(
            p_token: token,
            p_app_version: AppConfig.versionName,
            p_os_version: UIDevice.current.systemVersion,
            p_locale: Locale.current.identifier,
            p_timezone: TimeZone.current.identifier,
            p_platform: "ios"
        )
        _ = try? await client.rpc("register_device", params: params).execute()
    }

    @MainActor
    static func unregister(token: String, client: SupabaseClient?) async {
        guard let client else { return }
        _ = try? await client.rpc("unregister_device", params: UnregisterParams(p_token: token)).execute()
    }
}

private struct RegisterParams: Encodable {
    var p_token: String
    var p_app_version: String
    var p_os_version: String
    var p_locale: String
    var p_timezone: String
    var p_platform: String
}

private struct UnregisterParams: Encodable { var p_token: String }

/// Asks the backend (send-test-push) to send a test alert to THIS phone's push token. Returns the text to show.
enum TestAlert {
    @MainActor
    static func send(client: SupabaseClient?, push: LivePush) async -> String {
        guard AppConfig.pushPlistPresent else {
            return String(localized: "Push is not configured. Add GoogleService-Info.plist from the Firebase project. The app still runs.")
        }
        guard let client else { return String(localized: "Backend not configured.") }
        guard let token = await push.currentToken() else { return notRegistered }
        // Signing out or into another account leaves this phone's device row on the old account until the
        // next cold launch, and the backend only sends to the caller's own devices. Re-register first (an
        // upsert on the token) so the test reaches this phone and real alerts follow the current account.
        await DeviceRegistrar.register(token: token, client: client)
        do {
            let response: TestAlertResponse = try await client.functions.invoke(
                "send-test-push",
                options: FunctionInvokeOptions(body: TestAlertRequest(token: token))
            )
            return response.sent
                ? String(localized: "Test alert sent. It should arrive in a few seconds.")
                : String(localized: "Firebase did not accept the test alert. Alerts will not reach this phone yet.")
        } catch FunctionsError.httpError(let code, let data) {
            let error = (try? JSONDecoder().decode(FunctionErrorBody.self, from: data))?.error
            if error == "not_registered" { return notRegistered }
            if error == "rate_limited" || code == 429 { return String(localized: "Too many test alerts. Try again in 10 minutes.") }
            return String(localized: "Could not send the test alert. Try again later.")
        } catch is URLError {
            return String(localized: "You are offline. Connect to the internet and try again.")
        } catch {
            return String(localized: "Could not send the test alert. Try again later.")
        }
    }

    private static var notRegistered: String {
        String(localized: "This phone is not registered for alerts yet. Close the app fully, open it again, then try again.")
    }
}

private struct TestAlertRequest: Encodable { var token: String }
private struct TestAlertResponse: Decodable { var sent: Bool }

@MainActor
final class OnDeviceExtractor: TicketTextExtracting {
    func extract(_ request: ExtractRequest) async throws -> ExtractedTicket {
        var chunks: [String] = []
        if let text = request.text, !text.isEmpty { chunks.append(text) }
        var kinds: [TicketMedia] = []
        for uri in request.uris {
            let url = URL(string: uri) ?? URL(fileURLWithPath: uri)
            let name = url.lastPathComponent
            let kind = mediaKind(mime: request.mimeType, name: name)
            kinds.append(kind == .unknown ? mediaKind(mime: nil, name: name) : kind)
            if kind == .pdf || name.lowercased().hasSuffix(".pdf") {
                chunks.append(pdfText(url))
            } else if kind == .image || (request.mimeType?.hasPrefix("image/") ?? false) {
                chunks.append(await imageText(url))
            } else if kind == .text {
                chunks.append((try? String(contentsOf: url, encoding: .utf8)) ?? "")
            }
        }
        var text = chunks.joined(separator: "\n")
        let truncated = text.count > maxTicketCharacters
        if truncated { text = String(text.prefix(maxTicketCharacters)) }
        let source = request.uris.isEmpty && request.text != nil ? TrackSource.share : trackSource(for: kinds.isEmpty ? [.text] : kinds)
        return ExtractedTicket(text: text, source: source, truncated: truncated)
    }

    private func pdfText(_ url: URL) -> String {
        guard let document = PDFDocument(url: url) else { return "" }
        let count = min(document.pageCount, maxPDFPages)
        return (0..<count).compactMap { document.page(at: $0)?.string }.joined(separator: "\n")
    }

    private func imageText(_ url: URL) async -> String {
        guard var image = UIImage(contentsOfFile: url.path) ?? (try? Data(contentsOf: url)).flatMap(UIImage.init(data:)) else { return "" }
        let size = scaledSize(width: Int(image.size.width * image.scale), height: Int(image.size.height * image.scale))
        if size.0 != Int(image.size.width * image.scale) {
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: size.0, height: size.1))
            image = renderer.image { _ in image.draw(in: CGRect(x: 0, y: 0, width: size.0, height: size.1)) }
        }
        guard let cg = image.cgImage else { return "" }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        try? handler.perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    }
}

enum GoogleLinker {
    @MainActor
    static func token() async -> Result<(idToken: String, accessToken: String?), GoogleFailure> {
        guard AppConfig.isGoogleConfigured, let ios = AppConfig.googleIOSClientID else { return .failure(.notConfigured) }
        #if canImport(GoogleSignIn)
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: ios, serverClientID: AppConfig.googleWebClientID)
        guard let presenter = topController() else { return .failure(.failed) }
        return await withCheckedContinuation { continuation in
            GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
                if let error, (error as NSError).code == GIDSignInError.Code.canceled.rawValue {
                    continuation.resume(returning: .failure(.cancelled))
                    return
                }
                guard let user = result?.user, let idToken = user.idToken?.tokenString else {
                    continuation.resume(returning: .failure(.missingCredential))
                    return
                }
                continuation.resume(returning: .success((idToken, user.accessToken.tokenString)))
            }
        }
        #else
        return .failure(.notConfigured)
        #endif
    }

    enum GoogleFailure: Error { case cancelled, missingCredential, notConfigured, failed }
}

@MainActor
private func topController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let root = scenes.flatMap(\.windows).first { $0.isKeyWindow }?.rootViewController
    var current = root
    while let presented = current?.presentedViewController { current = presented }
    return current
}

enum ShareInbox {
    static func consume() -> SharedContent? {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup) else { return nil }
        let manifest = container.appendingPathComponent("pending-import.json")
        guard let data = try? Data(contentsOf: manifest),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        try? FileManager.default.removeItem(at: manifest)
        let files = (object["files"] as? [String]) ?? []
        let text = object["text"] as? String
        let content = SharedContent(uris: files, mimeType: object["mime"] as? String, text: text)
        return content.isEmpty ? nil : content
    }
}
