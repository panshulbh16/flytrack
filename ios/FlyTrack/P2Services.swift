import FlyTrackCore
import Foundation
import Supabase
import UserNotifications

// Backend adapters for the P2 contract (docs/P2_CONTRACTS.md): Airport Health, Passport, alert preferences and
// delivery receipts. Every adapter returns `.notConfigured` (or does nothing) when the xcconfig keys are blank.

/// `get_airport_health(p_iata)`. Answers are kept for 5 minutes per airport, so opening a few flights at the same
/// airport asks once.
@MainActor
final class LiveAirportHealth: AirportHealthSource {
    private static let maxAge: TimeInterval = 5 * 60
    private let stack: SupabaseStack
    private var cache: [String: (at: Date, health: AirportHealth)] = [:]

    init(stack: SupabaseStack) { self.stack = stack }

    func health(iata: String) async -> AirportHealthResult {
        let code = iata.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if let hit = cache[code], Date().timeIntervalSince(hit.at) < Self.maxAge { return .health(hit.health) }
        guard let client = stack.client else { return .notConfigured }
        guard client.auth.currentSession != nil else { return .failed }
        do {
            let data = try await client.rpc("get_airport_health", params: AirportHealthParams(p_iata: code)).execute().data
            guard let health = parseAirportHealth(data, requestedIata: code) else { return .unavailable }
            cache[code] = (Date(), health)
            return .health(health)
        } catch is URLError {
            return .offline
        } catch {
            return .failed
        }
    }
}

private struct AirportHealthParams: Encodable { var p_iata: String }

/// `my_passport(p_year)`. A null year is sent as JSON null (all time for Pro, last 12 months for free).
@MainActor
final class LivePassport: PassportSource {
    private let stack: SupabaseStack
    init(stack: SupabaseStack) { self.stack = stack }

    func passport(year: Int?) async -> PassportResult {
        guard let client = stack.client else { return .notConfigured }
        guard client.auth.currentSession != nil else { return .failed }
        do {
            let data = try await client.rpc("my_passport", params: PassportParams(p_year: year)).execute().data
            let fallback = year ?? passportToday(Date()).year
            guard let passport = parsePassport(data, fallbackYear: fallback) else { return .failed }
            return .passport(passport)
        } catch is URLError {
            return .offline
        } catch {
            return .failed
        }
    }
}

private struct PassportParams: Encodable {
    var p_year: Int?

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(p_year, forKey: .p_year) // explicit null: the RPC's default
    }

    private enum CodingKeys: String, CodingKey { case p_year }
}

/// `profiles` quiet hours and `alert_language` (migration 15 column grants).
@MainActor
final class LiveAlertPrefs: AlertPrefsRepository {
    private static let columns = "quiet_hours_enabled,quiet_start,quiet_end,quiet_tz,alert_language"
    private let stack: SupabaseStack
    init(stack: SupabaseStack) { self.stack = stack }

    private var userId: String? { stack.client?.auth.currentSession?.user.id.uuidString.lowercased() }

    func load() async -> Result<AlertPrefs?, AlertPrefsError> {
        guard let client = stack.client else { return .failure(.notConfigured) }
        guard let userId else { return .success(nil) }
        do {
            let data = try await client.from("profiles").select(Self.columns).eq("id", value: userId).execute().data
            return .success(alertPrefs(fromProfilesJSON: data))
        } catch is URLError {
            return .failure(.offline)
        } catch {
            return .failure(.failed)
        }
    }

    func save(_ change: AlertPrefsChange, deviceTz: String) async -> Result<Void, AlertPrefsError> {
        guard let client = stack.client else { return .failure(.notConfigured) }
        guard let userId else { return .failure(.failed) }
        do {
            try await client.from("profiles").update(alertPrefsPatch(change, deviceTz: deviceTz)).eq("id", value: userId).execute()
            return .success(())
        } catch is URLError {
            return .failure(.offline)
        } catch {
            return .failure(.failed)
        }
    }

    /// The contract's default for a profile that never chose: Hindi when the phone's language is Hindi. Written once
    /// per install, only while the server still has the column default `en`, so a later choice is never overwritten.
    func applyDeviceDefaultOnce(defaults: UserDefaults = .standard) async {
        let key = "alert_language_default_applied"
        guard !defaults.bool(forKey: key) else { return }
        let language = defaultAlertLanguage(deviceLanguage: Locale.preferredLanguages.first)
        guard language != .en else {
            defaults.set(true, forKey: key)
            return
        }
        guard case .success(let prefs?) = await load() else { return }
        if prefs.language == .en, case .success = await save(.language(language), deviceTz: TimeZone.current.identifier) {
            defaults.set(true, forKey: key)
        } else if prefs.language != .en {
            defaults.set(true, forKey: key)
        }
    }
}

/// `POST ack-push` with the user's JWT. Fire-and-forget: every failure is ignored (contract section 3).
@MainActor
final class LivePushAck: PushAckSending {
    private let stack: SupabaseStack
    init(stack: SupabaseStack) { self.stack = stack }

    func ack(_ requests: [PushAckRequest]) async {
        guard let client = stack.client, client.auth.currentSession != nil else { return }
        for request in requests {
            do {
                try await client.functions.invoke(PushAckContract.function, options: FunctionInvokeOptions(body: request))
            } catch {
                // Ignored on purpose: a lost receipt only makes the latency report a little thinner.
            }
        }
    }
}

/// Delivery receipts: an alert is acknowledged the first time the app sees it, which is the earliest of
/// * shown in the foreground (`willPresent`) or posted by the app itself,
/// * opened (`didReceive`),
/// * found in Notification Center when the app comes to the foreground, stamped with the time iOS delivered it.
@MainActor
final class PushReceipts {
    private let sender: PushAckSending
    private let acked = AckedEvents()
    private static let sweepWindow: TimeInterval = 24 * 3600

    init(sender: PushAckSending) { self.sender = sender }

    func received(_ items: [(eventId: String?, receivedAt: Date)]) {
        let requests = pushAcks(for: items, acked: acked)
        guard !requests.isEmpty else { return }
        let sender = sender
        Task { await sender.ack(requests) }
    }

    /// Alerts that arrived while the app was in the background or killed, using their delivery time.
    /// Only the last day's alerts: older ones were acknowledged on an earlier app open (the server keeps the first
    /// receipt only), so each foreground does not resend the whole Notification Center.
    func sweepDelivered() async {
        let since = Date().addingTimeInterval(-Self.sweepWindow)
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications().filter { $0.date > since }
        received(delivered.map { notification in
            (eventId: notification.request.content.userInfo[PushContract.keyEventId] as? String, receivedAt: notification.date)
        })
    }
}
