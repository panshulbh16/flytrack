import AuthenticationServices
import Combine
import CoreLocation
import FlyTrackCore
import Foundation
import Supabase
import UIKit

// Backend adapters and device services for P4 (docs/P4_CONTRACTS.md): radar (section 1), TripIt (section 2), Gift Pro
// (section 3) and the bundled terminal maps (section 4). The logic is in FlyTrackCore (Radar.swift, TripIt.swift,
// Gifts.swift, TerminalMap.swift); everything here only talks to the network, the store, CoreLocation and the bundle,
// returns typed results and never throws.

/// The P4 services the screens use. Owned by `AppShell`.
@MainActor
final class P4Center: ObservableObject {
    let functions: P4Functions
    let radar: LiveRadar
    let location: CoarseLocation
    let tripIt: LiveTripIt
    let tripItAuthorizer: WebTripItAuthorizer
    let gifts: LiveGifts
    let giftStore: GiftStore
    let terminalMaps: BundledTerminalMaps

    /// A `heygate://tripit/...` link the system opened (a browser that handed TripIt's redirect to the app). The
    /// TripIt section in Settings consumes it.
    @Published var pendingTripItCallback: URL?
    /// True while the Settings screen is on top, so a callback link does not push a second Settings screen.
    var settingsVisible = false

    init(stack: SupabaseStack, auth: LiveAuthRepository, backend: Wave2Backend, billing: BillingGateway) {
        let functions = P4Functions(stack: stack, auth: auth)
        self.functions = functions
        radar = LiveRadar(functions: functions)
        location = CoarseLocation()
        tripIt = LiveTripIt(functions: functions, backend: backend)
        tripItAuthorizer = WebTripItAuthorizer()
        gifts = LiveGifts(backend: backend)
        giftStore = makeGiftStore(billing)
        terminalMaps = BundledTerminalMaps()
    }
}

// MARK: - Edge Functions

/// Calls an Edge Function with the user's JWT and hands back the raw status and body, so FlyTrackCore maps the
/// contract's `{ "error": ... }` answers itself (supabase-swift throws away the body shape on non-2xx).
@MainActor
final class P4Functions {
    enum Answer: Sendable {
        case ok(Data)
        case http(status: Int, body: Data)
        case offline
        case notConfigured
        case failed
    }

    private let stack: SupabaseStack
    private let auth: LiveAuthRepository
    private let session: URLSession

    init(stack: SupabaseStack, auth: LiveAuthRepository, session: URLSession = .shared) {
        self.stack = stack
        self.auth = auth
        self.session = session
    }

    func get(_ name: String, query: [URLQueryItem]) async -> Answer { await call(name, method: "GET", query: query) }

    /// POST with an empty JSON object (`tripit-connect`, `tripit-sync`, `tripit-disconnect`).
    func post(_ name: String) async -> Answer { await call(name, method: "POST", query: []) }

    private func call(_ name: String, method: String, query: [URLQueryItem]) async -> Answer {
        guard let client = stack.client, let base = AppConfig.supabaseURL, let key = AppConfig.anonKey else { return .notConfigured }
        switch await auth.ensureSignedIn() {
        case .success, .googleAccountInUse: break
        case .notConfigured: return .notConfigured
        case .offline: return .offline
        case .failed: return .failed
        }
        let token: String
        do {
            token = try await client.auth.session.accessToken
        } catch is URLError {
            return .offline
        } catch {
            return .failed
        }
        var components = URLComponents(url: base.appendingPathComponent("functions/v1/\(name)"), resolvingAgainstBaseURL: false)
        if !query.isEmpty { components?.queryItems = query }
        guard let url = components?.url else { return .failed }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(key, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if method == "POST" {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data("{}".utf8)
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed }
            return (200..<300).contains(http.statusCode) ? .ok(data) : .http(status: http.statusCode, body: data)
        } catch is URLError {
            return .offline
        } catch {
            return .failed
        }
    }
}

// MARK: - radar (section 1)

/// `GET radar?mode=...`. The model rounds the location to 0.05° before it reaches this request.
@MainActor
final class LiveRadar: RadarFetching {
    private let functions: P4Functions
    init(functions: P4Functions) { self.functions = functions }

    func radar(_ request: RadarRequest) async -> RadarFetchResult {
        guard let query = request.queryItems else { return .failure(.failed) }
        switch await functions.get(RadarContract.function, query: query) {
        case .ok(let data): return parseRadarResponse(data).map(RadarFetchResult.ok) ?? .failure(.failed)
        case .http(let status, let body): return .failure(radarFailure(status: status, body: body))
        case .offline: return .failure(.offline)
        case .notConfigured: return .failure(.notConfigured)
        case .failed: return .failure(.failed)
        }
    }
}

/// When-in-use, reduced-accuracy location (P4 section 1: coarse only, asked for on tap). Info.plist has
/// `NSLocationWhenInUseUsageDescription` and `NSLocationDefaultAccuracyReduced = true`, so iOS hands out an
/// approximate fix unless the user turns precise location on, and the radar model rounds it to 0.05° anyway.
@MainActor
final class CoarseLocation: NSObject, CoarseLocating, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authorizationWaiters: [CheckedContinuation<Void, Never>] = []
    private var fixWaiters: [CheckedContinuation<CoarseLocationResult, Never>] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyReduced
    }

    func authorization() -> CoarseLocationAuthorization { Self.map(manager.authorizationStatus) }

    func requestCoarseLocation() async -> CoarseLocationResult {
        if manager.authorizationStatus == .notDetermined {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                authorizationWaiters.append(continuation)
                if authorizationWaiters.count == 1 { manager.requestWhenInUseAuthorization() }
            }
        }
        switch authorization() {
        case .denied, .restricted: return .denied
        case .notDetermined: return .unavailable
        case .authorized: break
        }
        return await withCheckedContinuation { (continuation: CheckedContinuation<CoarseLocationResult, Never>) in
            fixWaiters.append(continuation)
            if fixWaiters.count == 1 { manager.requestLocation() }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated {
            guard self.manager.authorizationStatus != .notDetermined else { return }
            let waiters = authorizationWaiters
            authorizationWaiters = []
            for waiter in waiters { waiter.resume() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let point = GeoPoint(lat: last.coordinate.latitude, lon: last.coordinate.longitude)
        MainActor.assumeIsolated { finish(.located(point)) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let denied = (error as? CLError)?.code == .denied
        MainActor.assumeIsolated { finish(denied ? .denied : .unavailable) }
    }

    private func finish(_ result: CoarseLocationResult) {
        let waiters = fixWaiters
        fixWaiters = []
        for waiter in waiters { waiter.resume(returning: result) }
    }

    private static func map(_ status: CLAuthorizationStatus) -> CoarseLocationAuthorization {
        switch status {
        case .notDetermined: return .notDetermined
        case .authorizedWhenInUse, .authorizedAlways: return .authorized
        case .restricted: return .restricted
        case .denied: return .denied
        @unknown default: return .denied
        }
    }
}

// MARK: - TripIt (section 2)

/// `tripit-connect` / `tripit-sync` / `tripit-disconnect` (POST, user JWT) and the `tripit_status()` RPC.
@MainActor
final class LiveTripIt: TripItRepository {
    private let functions: P4Functions
    private let backend: Wave2Backend

    init(functions: P4Functions, backend: Wave2Backend) {
        self.functions = functions
        self.backend = backend
    }

    func status() async -> Result<TripItStatus, TripItFailure> {
        let result: RemoteResult<TripItStatus> = await backend.call { client, _ in
            guard let status = parseTripItStatus(try await client.rpc(TripItContract.rpcStatus).execute().data) else { throw P3UnexpectedAnswer() }
            return status
        }
        switch result {
        case .ok(let status): return .success(status)
        case .offline: return .failure(.offline)
        case .notConfigured: return .failure(.backendMissing)
        case .refused, .failed: return .failure(.failed)
        }
    }

    func authorizeURL() async -> Result<URL, TripItFailure> {
        switch await functions.post(TripItContract.functionConnect) {
        case .ok(let data): return parseTripItAuthorizeURL(data).map { .success($0) } ?? .failure(.failed)
        case let answer: return .failure(mapFailure(answer))
        }
    }

    func sync() async -> Result<TripItSyncSummary, TripItFailure> {
        switch await functions.post(TripItContract.functionSync) {
        case .ok(let data): return parseTripItSyncSummary(data).map { .success($0) } ?? .failure(.failed)
        case let answer: return .failure(mapFailure(answer))
        }
    }

    func disconnect() async -> Result<Void, TripItFailure> {
        switch await functions.post(TripItContract.functionDisconnect) {
        case .ok: return .success(())
        case let answer: return .failure(mapFailure(answer))
        }
    }

    private func mapFailure(_ answer: P4Functions.Answer) -> TripItFailure {
        switch answer {
        case .ok: return .failed
        case .http(let status, let body): return tripItFailure(status: status, body: body)
        case .offline: return .offline
        case .notConfigured: return .backendMissing
        case .failed: return .failed
        }
    }
}

/// TripIt's sign-in page in ASWebAuthenticationSession; the session catches the `heygate://tripit/...` redirect
/// itself. Not ephemeral: a user already signed in to TripIt in Safari only has to tap Allow.
@MainActor
final class WebTripItAuthorizer: NSObject, TripItAuthorizing, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authorize(url: URL, callbackScheme: String) async -> TripItAuthorizationResult {
        session?.cancel()
        let result = await withCheckedContinuation { (continuation: CheckedContinuation<TripItAuthorizationResult, Never>) in
            let session = ASWebAuthenticationSession(url: url, callback: .customScheme(callbackScheme)) { @Sendable callbackURL, error in
                if let callbackURL {
                    continuation.resume(returning: .callback(callbackURL))
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(returning: .cancelled)
                } else {
                    continuation.resume(returning: .failed)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            // Without a start the completion handler never runs.
            if !session.start() { continuation.resume(returning: .failed) }
        }
        session = nil
        return result
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { Self.keyWindow() }
    }

    private static func keyWindow() -> UIWindow {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        if let window = active?.keyWindow ?? active?.windows.first { return window }
        if let active { return UIWindow(windowScene: active) }
        return UIWindow(frame: UIScreen.main.bounds)
    }
}

// MARK: - gifts (section 3)

private struct RedeemGiftParams: Encodable, Sendable { var p_code: String }

/// `my_gifts()` and `redeem_gift(p_code)`.
@MainActor
final class LiveGifts: GiftsRepository {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func myGifts() async -> RemoteResult<[Gift]> {
        await backend.call { client, _ in
            guard let list = parseMyGifts(try await client.rpc(GiftContract.rpcMyGifts).execute().data) else { throw P3UnexpectedAnswer() }
            return list
        }
    }

    func redeem(code: String) async -> RedeemGiftResult {
        let result: RemoteResult<RedeemGiftResult> = await backend.call { client, _ in
            parseRedeemGift(try await client.rpc(GiftContract.rpcRedeem, params: RedeemGiftParams(p_code: code)).execute().data)
        }
        switch result {
        case .ok(let answer): return answer
        case .offline: return .offline
        case .notConfigured: return .notConfigured
        // An older server that raises instead of returning {error}: the SQLSTATE or the message names the error.
        case .refused(let code, let message):
            let byMessage = message.map(redeemGiftError) ?? .failed
            return byMessage != .failed ? byMessage : (code.map(redeemGiftError) ?? .failed)
        case .failed: return .failed
        }
    }
}

// MARK: - terminal maps (section 4)

/// `Resources/TerminalMaps/{IATA}.geojson` from the app bundle (a folder reference, see project.yml), read off the
/// main actor and kept for the session.
@MainActor
final class BundledTerminalMaps: TerminalMapLoading {
    private var cache: [String: Data] = [:]

    func geojson(iata: String) async -> Data? {
        let code = iata.uppercased()
        if let cached = cache[code] { return cached }
        let data = await Task.detached(priority: .userInitiated) { () -> Data? in
            guard let url = Bundle.main.url(
                forResource: code, withExtension: TerminalMapContract.fileExtension, subdirectory: TerminalMapContract.resourceDirectory
            ) else { return nil }
            return try? Data(contentsOf: url, options: .mappedIfSafe)
        }.value
        if let data { cache[code] = data }
        return data
    }

    /// True when this build ships a file for the airport (the airport cards only offer the map then).
    nonisolated static func hasMap(_ iata: String) -> Bool {
        Bundle.main.url(
            forResource: iata.uppercased(), withExtension: TerminalMapContract.fileExtension, subdirectory: TerminalMapContract.resourceDirectory
        ) != nil
    }
}
