import FlyTrackCore
import Foundation

enum AppConfig {
    static let appGroup = "group.app.heygate.flytrack"

    static var supabaseURL: URL? {
        guard let raw = string("SupabaseURL"), let url = URL(string: raw), let scheme = url.scheme, scheme == "https" else {
            return nil
        }
        return url
    }

    static var anonKey: String? { string("SupabaseAnonKey") }
    static var googleIOSClientID: String? { string("GoogleIOSClientID") }
    static var googleWebClientID: String? { string("GoogleWebClientID") }

    /// RevenueCat public iOS SDK key (REVENUECAT_IOS_KEY). Nil: billing is off and the paywall says "Pro coming soon".
    static var revenueCatKey: String? { string("RevenueCatAPIKey") }

    /// SHARE_BASE_URL, or the contract default when blank or not an https/http URL. Shares are `<base>#<token>`.
    static var shareBaseURL: String {
        guard let raw = string("ShareBaseURL"), let url = URL(string: raw), url.scheme == "https" || url.scheme == "http", url.host != nil else {
            return ShareContract.defaultBaseURL
        }
        return raw
    }

    static var isBackendConfigured: Bool { supabaseURL != nil && anonKey != nil }
    static var isGoogleConfigured: Bool { googleIOSClientID != nil && googleWebClientID != nil }
    /// The GMAIL_IMPORT_ENABLED release switch, and a build that can sign in to Google.
    static var isGmailImportEnabled: Bool { string("GmailImportEnabled") == "YES" && isGoogleConfigured }

    static var versionName: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(version) (\(build))"
    }

    static var pushPlistPresent: Bool {
        Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil
    }

    private static func string(_ key: String) -> String? {
        let raw = (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if raw.isEmpty || raw.hasPrefix("$(") || raw.contains("YOUR_") || raw == "PLACEHOLDER" { return nil }
        return raw
    }
}
