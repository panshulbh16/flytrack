import Foundation

// Friends ("Mom's flight has landed"), P2 wave 2 contract section 1. Same rules as Android's feature/friends.

// MARK: - invite codes

public enum InviteCode {
    /// The 31 characters of a friend code: no 0/O, 1/I/L look-alikes.
    public static let alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    public static let length = 8
    /// "ABCD-EFGH" plus a little slack for stray spaces.
    public static let maxInputLength = 12

    fileprivate static let characters = Set(alphabet)
    fileprivate static let dashed = "(?<![A-Z0-9-])[\(alphabet)]{4}-[\(alphabet)]{4}(?![A-Z0-9-])"
    fileprivate static let plain = "(?<![A-Z0-9-])[\(alphabet)]{8}(?![A-Z0-9-])"
}

/// A typed or pasted code in its canonical form ("ABCDEFGH"), or nil when it cannot be a code. Spaces and dashes are
/// ignored and letters upper-cased, like the server's `accept_friend_invite`.
public func normalizeInviteCode(_ input: String) -> String? {
    let code = String(input.uppercased().filter { !$0.isWhitespace && $0 != "-" })
    guard code.count == InviteCode.length, code.allSatisfy({ InviteCode.characters.contains($0) }) else { return nil }
    return code
}

/// Finds a friend code in pasted text, such as the whole invite message ("... code ABCD-EFGH ..."). A bare code
/// wins; then the first "ABCD-EFGH" group (the form the invite message uses); then the first 8-character word made
/// only of the alphabet that also contains a digit, so ordinary words like "WEEKENDS" are not taken for a code.
/// Matching is case-insensitive.
public func extractInviteCode(_ text: String) -> String? {
    if let code = normalizeInviteCode(text) { return code }
    let upper = text.uppercased()
    if let found = RE.first(InviteCode.dashed, in: upper) {
        return found.0.substring(with: found.1.range).replacingOccurrences(of: "-", with: "")
    }
    for (ns, match) in RE.matches(InviteCode.plain, in: upper) {
        let candidate = ns.substring(with: match.range)
        if candidate.contains(where: \.isNumber) { return candidate }
    }
    return nil
}

/// "ABCD-EFGH": easier to read aloud and to type. The server ignores the dash.
public func formatInviteCode(_ code: String) -> String {
    guard code.count == InviteCode.length else { return code }
    return "\(code.prefix(4))-\(code.dropFirst(4))"
}

/// What the accept field keeps of a typed or pasted value: a pasted invite message shrinks to the code it contains,
/// anything else is upper-cased and cut at `InviteCode.maxInputLength`.
public func inviteInput(_ value: String) -> String {
    if value.count > InviteCode.length + 1, let code = extractInviteCode(value) { return formatInviteCode(code) }
    return String(value.uppercased().prefix(InviteCode.maxInputLength))
}

/// `heygate://friend?code=ABCDEFGH` or `heygate://friend/ABCD-EFGH`: the code, or nil for any other link.
public func inviteCode(fromURL url: URL) -> String? {
    guard url.scheme?.lowercased() == "heygate", url.host?.lowercased() == "friend" else { return nil }
    let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value
    let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    return normalizeInviteCode(query ?? path)
}

/// The link that opens the accept screen with `code` filled in.
public func inviteLink(code: String) -> String { "heygate://friend?code=\(code)" }

// MARK: - models

/// One row of `my_friends()`. `displayName` nil = the friend set no name ("Your friend").
public struct Friend: Hashable, Sendable, Identifiable {
    public var id: String
    public var displayName: String?
    /// I get alerts for this friend's flights.
    public var alertsEnabled: Bool
    public var since: Date?

    public init(id: String, displayName: String?, alertsEnabled: Bool, since: Date?) {
        self.id = id
        self.displayName = displayName
        self.alertsEnabled = alertsEnabled
        self.since = since
    }
}

/// One row of `friends_flights()`: a flight a friend tracks and shares.
public struct FriendFlight: Hashable, Sendable {
    public var friendId: String
    public var friendName: String?
    public var flight: Flight

    public init(friendId: String, friendName: String?, flight: Flight) {
        self.friendId = friendId
        self.friendName = friendName
        self.flight = flight
    }
}

/// `accept_friend_invite` succeeded.
public struct AcceptedFriend: Hashable, Sendable {
    public var friendId: String
    public var displayName: String?

    public init(friendId: String, displayName: String?) {
        self.friendId = friendId
        self.displayName = displayName
    }
}

// MARK: - wire format

private func cleanName(_ name: String?) -> String? {
    let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return trimmed.isEmpty ? nil : trimmed
}

/// `my_friends()`, newest friend first as the server orders them. Nil when the answer is not a list; rows without a
/// friend id are skipped. SQL null is an empty list.
public func parseFriends(_ data: Data) -> [Friend]? {
    if rpcIsNull(data) { return [] }
    guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
    return rows.compactMap { value in
        guard let row = value as? [String: Any], let id = JSON.string(row["friend_id"]) else { return nil }
        return Friend(
            id: id,
            displayName: cleanName(JSON.string(row["display_name"])),
            alertsEnabled: JSON.bool(row["alerts_enabled"]) ?? true,
            since: parseInstant(JSON.string(row["since"]))
        )
    }
}

/// `friends_flights()`. Nil when the answer is not a list. Rows without a usable flight (no id, carrier, number,
/// date or airports) are skipped.
public func parseFriendsFlights(_ data: Data) -> [FriendFlight]? {
    if rpcIsNull(data) { return [] }
    guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
    return rows.compactMap { value in
        guard let row = value as? [String: Any],
              let friendId = JSON.string(row["friend_id"]),
              let flightId = JSON.string(row["flight_id"]),
              let object = JSON.object(row["flight"]),
              let flight = publicFlight(object, id: flightId) else { return nil }
        return FriendFlight(friendId: friendId, friendName: cleanName(JSON.string(row["display_name"])), flight: flight)
    }
}

/// `accept_friend_invite`. Nil without a friend id.
public func parseAcceptedFriend(_ data: Data) -> AcceptedFriend? {
    guard let object = JSON.object(from: data), let id = JSON.string(object["friend_id"]) else { return nil }
    return AcceptedFriend(friendId: id, displayName: cleanName(JSON.string(object["display_name"])))
}

/// `flight_public_json` (migration 17): the shared-flight shape. Nil when a required field is missing.
public func publicFlight(_ object: [String: Any], id: String) -> Flight? {
    guard let carrier = JSON.string(object["carrier"])?.trimmingCharacters(in: .whitespacesAndNewlines), !carrier.isEmpty,
          let number = JSON.string(object["number"])?.trimmingCharacters(in: .whitespacesAndNewlines), !number.isEmpty,
          let date = JSON.string(object["date"]).flatMap({ LocalDate.parse(String($0.prefix(10))) }),
          let dep = JSON.object(object["dep"]), let depIata = JSON.string(dep["iata"]),
          let arr = JSON.object(object["arr"]), let arrIata = JSON.string(arr["iata"]) else { return nil }
    func airport(_ end: [String: Any], iata: String) -> Airport? {
        guard let tz = JSON.string(end["tz"]), let lat = JSON.double(end["lat"]), let lon = JSON.double(end["lon"]) else { return nil }
        return Airport(iata: iata, name: JSON.string(end["name"]) ?? iata, city: JSON.string(end["city"]), tz: tz, lat: lat, lon: lon)
    }
    let live: LivePosition? = JSON.object(object["live"]).flatMap { l in
        guard let lat = JSON.double(l["lat"]), let lon = JSON.double(l["lon"]) else { return nil }
        return LivePosition(
            lat: lat, lon: lon, altFt: JSON.int(l["alt_ft"]), headingDeg: JSON.double(l["heading"]),
            speedKts: JSON.double(l["speed_kts"]), at: parseInstant(JSON.string(l["at"]))
        )
    }
    return Flight(
        id: id,
        carrierIata: carrier,
        flightNumber: number,
        flightDate: date,
        depIata: depIata,
        arrIata: arrIata,
        airlineName: JSON.string(object["airline"]),
        status: FlightStatus.fromWire(JSON.string(object["status"])),
        schedDep: parseInstant(JSON.string(object["sched_dep"])),
        estDep: parseInstant(JSON.string(object["est_dep"])),
        actDep: parseInstant(JSON.string(object["act_dep"])),
        depTerminal: JSON.string(dep["terminal"]),
        depGate: JSON.string(dep["gate"]),
        schedArr: parseInstant(JSON.string(object["sched_arr"])),
        estArr: parseInstant(JSON.string(object["est_arr"])),
        actArr: parseInstant(JSON.string(object["act_arr"])),
        arrTerminal: JSON.string(arr["terminal"]),
        arrGate: JSON.string(arr["gate"]),
        baggageBelt: JSON.string(arr["belt"]),
        aircraftModel: JSON.object(object["aircraft"]).flatMap { JSON.string($0["model"]) },
        live: live,
        updatedAt: parseInstant(JSON.string(object["updated_at"])),
        depAirport: airport(dep, iata: depIata),
        arrAirport: airport(arr, iata: arrIata)
    )
}

// MARK: - accept errors

/// Result of `accept_friend_invite`.
public enum AcceptInviteResult: Sendable, Equatable {
    case accepted(AcceptedFriend)
    /// Unknown, used or expired code (`invalid_code`, P0002), or text that cannot be a code.
    case invalidCode
    /// The user typed their own code (`own_code`, 22023).
    case ownCode
    /// `already_friends` (23505).
    case alreadyFriends
    /// One of the two already has 50 friends (`friend_limit`, P0001).
    case friendLimit
    case offline
    case notConfigured
    /// Technical English: log it, do not show it.
    case failed(String?)
}

/// Maps an `accept_friend_invite` error (contract codes, with the raised message as a second clue).
public func acceptInviteError(code: String?, message: String?) -> AcceptInviteResult {
    let text = message ?? ""
    if code == "P0002" || text.contains("invalid_code") { return .invalidCode }
    if code == "22023" || text.contains("own_code") { return .ownCode }
    if code == "23505" || text.contains("already_friends") { return .alreadyFriends }
    if text.contains("friend_limit") || code == "P0001" { return .friendLimit }
    return .failed(message ?? code)
}

/// Turns the RPC outcome into the typed result.
public func acceptInviteResult(_ result: RemoteResult<AcceptedFriend>) -> AcceptInviteResult {
    switch result {
    case .ok(let friend): return .accepted(friend)
    case .offline: return .offline
    case .notConfigured: return .notConfigured
    case .failed(let message): return .failed(message)
    case .refused(let code, let message): return acceptInviteError(code: code, message: message)
    }
}

// MARK: - repository

/// Friends RPCs. Every call is typed and never throws.
@MainActor
public protocol FriendsRepository: AnyObject {
    /// `create_friend_invite()`: the user's open code (reused for 7 days) or a new one.
    func createInvite() async -> RemoteResult<String>
    /// `accept_friend_invite(p_code)`. Text that cannot be a code is `.invalidCode` without a call.
    func acceptInvite(code: String) async -> AcceptInviteResult
    /// `my_friends()`.
    func friends() async -> RemoteResult<[Friend]>
    /// `remove_friend(p_friend)`: ends the friendship both ways.
    func removeFriend(_ friendId: String) async -> RemoteResult<Bool>
    /// `set_friend_alerts(p_friend, p_enabled)`: the alerts I get for that friend's flights.
    func setFriendAlerts(_ friendId: String, enabled: Bool) async -> RemoteResult<Bool>
    /// `friends_flights()`.
    func friendsFlights() async -> RemoteResult<[FriendFlight]>
    /// `user_flights.share_with_friends` of the user's own flight; `.ok(nil)` when the user does not track it.
    func shareWithFriends(flightId: String) async -> RemoteResult<Bool?>
    /// Sets `share_with_friends`; `.ok(nil)` when the user does not track the flight.
    func setShareWithFriends(flightId: String, share: Bool) async -> RemoteResult<Bool?>
}

// MARK: - friends screen

public enum FriendsMessage: String, Sendable, Equatable {
    case offline, failed, removed
}

public enum FriendsLoad: String, Sendable, Equatable {
    case loading, loaded, offline, notConfigured, failed
}

/// Where an invite goes once its code is ready. `copy` puts the code on the pasteboard.
public enum InviteTarget: String, Sendable, Equatable {
    case whatsApp, shareSheet, copy
}

/// A code is ready: the screen builds the localised invite text and hands it to `target`.
public struct InviteShareRequest: Hashable, Sendable {
    public var code: String
    public var target: InviteTarget

    public init(code: String, target: InviteTarget) {
        self.code = code
        self.target = target
    }
}

public struct FriendsState: Equatable, Sendable {
    public var load: FriendsLoad = .loading
    public var friends: [Friend] = []
    /// The user's open invite code, once created.
    public var inviteCode: String?
    public var isCreatingInvite = false
    public var inviteShare: InviteShareRequest?
    /// Friends whose remove or alert command is running: their controls are disabled.
    public var busy: Set<String> = []
    public var message: FriendsMessage?
    public var isRefreshing = false

    public init() {}
}

/// Friends screen: invite, list with alert switches, remove. Mirrors Android's FriendsViewModel.
@MainActor
public final class FriendsModel {
    public private(set) var state = FriendsState()
    public var onChange: ((FriendsState) -> Void)?
    private let repository: FriendsRepository
    private var loading = false

    public init(repository: FriendsRepository) {
        self.repository = repository
    }

    /// First load, pull-to-refresh and coming back from the accept screen.
    public func load() async {
        if loading { return }
        loading = true
        defer { loading = false }
        state.isRefreshing = state.load == .loaded
        publish()
        let result = await repository.friends()
        state.isRefreshing = false
        switch result {
        case .ok(let friends):
            state.load = .loaded
            state.friends = friends
        case .offline:
            // Keep a list that is already on screen; report the problem instead.
            if state.load == .loaded { state.message = .offline } else { state.load = .offline }
        case .notConfigured:
            state.load = .notConfigured
        case .refused, .failed:
            if state.load == .loaded { state.message = .failed } else { state.load = .failed }
        }
        publish()
    }

    /// "Invite on WhatsApp", "Share…" or "Copy code": creates the code when there is none yet (the server reuses an
    /// open one), then `inviteShare` carries it to the screen.
    public func invite(_ target: InviteTarget) async {
        if state.isCreatingInvite { return }
        if let code = state.inviteCode {
            state.inviteShare = InviteShareRequest(code: code, target: target)
            publish()
            return
        }
        state.isCreatingInvite = true
        publish()
        let result = await repository.createInvite()
        state.isCreatingInvite = false
        switch result {
        case .ok(let code):
            state.inviteCode = code
            state.inviteShare = InviteShareRequest(code: code, target: target)
        case .offline: state.message = .offline
        case .notConfigured: break
        case .refused, .failed: state.message = .failed
        }
        publish()
    }

    /// The screen handed `request` on.
    public func inviteHandled(_ request: InviteShareRequest) {
        guard state.inviteShare == request else { return }
        state.inviteShare = nil
        publish()
    }

    /// Alerts for a friend's flights on or off; the switch moves at once and goes back if the server refuses.
    public func setAlerts(_ friendId: String, enabled: Bool) async {
        guard let before = state.friends.first(where: { $0.id == friendId }),
              !state.busy.contains(friendId), before.alertsEnabled != enabled else { return }
        setAlertsLocally(friendId, enabled)
        state.busy.insert(friendId)
        publish()
        let result = await repository.setFriendAlerts(friendId, enabled: enabled)
        state.busy.remove(friendId)
        if !result.isOk {
            setAlertsLocally(friendId, before.alertsEnabled)
            state.message = message(for: result)
        }
        publish()
    }

    /// Ends the friendship (both ways). The screen confirms first.
    public func remove(_ friendId: String) async {
        guard !state.busy.contains(friendId) else { return }
        state.busy.insert(friendId)
        publish()
        let result = await repository.removeFriend(friendId)
        state.busy.remove(friendId)
        if result.isOk {
            state.friends.removeAll { $0.id == friendId }
            state.message = .removed
        } else {
            state.message = message(for: result)
        }
        publish()
    }

    public func messageShown(_ message: FriendsMessage) {
        guard state.message == message else { return }
        state.message = nil
        publish()
    }

    private func setAlertsLocally(_ friendId: String, _ enabled: Bool) {
        state.friends = state.friends.map { friend in
            var copy = friend
            if copy.id == friendId { copy.alertsEnabled = enabled }
            return copy
        }
    }

    private func message<T: Sendable>(for result: RemoteResult<T>) -> FriendsMessage? {
        switch result {
        case .ok, .notConfigured: return nil
        case .offline: return .offline
        case .refused, .failed: return .failed
        }
    }

    private func publish() { onChange?(state) }
}

// MARK: - accept screen

/// Why a code was not accepted; nil while the input is fine.
public enum AcceptError: String, Sendable, Equatable {
    case invalidCode, ownCode, alreadyFriends, friendLimit, offline, failed
}

public struct AcceptInviteState: Equatable, Sendable {
    public var input = ""
    public var isSubmitting = false
    public var error: AcceptError?
    /// Set once the friendship exists: the screen says so and offers to go back.
    public var accepted: AcceptedFriend?

    public init() {}

    /// The input holds something that can be a code (pasted messages included).
    public var canSubmit: Bool { !isSubmitting && accepted == nil && extractInviteCode(input) != nil }
}

/// "Enter a friend code" screen. Mirrors Android's AcceptInviteViewModel.
@MainActor
public final class AcceptInviteModel {
    public private(set) var state = AcceptInviteState()
    public var onChange: ((AcceptInviteState) -> Void)?
    private let repository: FriendsRepository

    public init(repository: FriendsRepository) {
        self.repository = repository
    }

    /// A code from outside (an invite link); used once, while the field is empty.
    public func prefill(_ code: String?) {
        guard let code, !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, state.input.isEmpty else { return }
        setInput(code)
    }

    public func setInput(_ value: String) {
        state.input = inviteInput(value)
        state.error = nil
        publish()
    }

    public func submit() async {
        guard state.canSubmit else {
            if !state.isSubmitting && state.accepted == nil {
                state.error = .invalidCode
                publish()
            }
            return
        }
        guard let code = extractInviteCode(state.input) else { return }
        state.isSubmitting = true
        state.error = nil
        publish()
        let result = await repository.acceptInvite(code: code)
        state.isSubmitting = false
        switch result {
        case .accepted(let friend): state.accepted = friend
        case .invalidCode: state.error = .invalidCode
        case .ownCode: state.error = .ownCode
        case .alreadyFriends: state.error = .alreadyFriends
        case .friendLimit: state.error = .friendLimit
        case .offline: state.error = .offline
        case .notConfigured: break
        case .failed: state.error = .failed
        }
        publish()
    }

    private func publish() { onChange?(state) }
}

// MARK: - friends' flights

/// One row of the "Friends' flights" list.
public struct FriendFlightRow: Hashable, Sendable {
    public var friendId: String
    /// Nil: "Your friend".
    public var friendName: String?
    public var card: FlightCard

    public init(friendId: String, friendName: String?, card: FlightCard) {
        self.friendId = friendId
        self.friendName = friendName
        self.card = card
    }
}

public struct FriendsFlightsState: Equatable, Sendable {
    public var load: FriendsLoad = .loading
    public var rows: [FriendFlightRow] = []
    public var isRefreshing = false

    public init() {}
}

/// Friends' flights that are upcoming or departed in the last 24 h (the server filters), in the server's order
/// (earliest departure first). Cards re-render from the last answer on `tick()`.
@MainActor
public final class FriendsFlightsModel {
    public private(set) var state = FriendsFlightsState()
    public var onChange: ((FriendsFlightsState) -> Void)?
    private let repository: FriendsRepository
    private let clock: FlyClock
    private var flights: [FriendFlight] = []

    public init(repository: FriendsRepository, clock: FlyClock) {
        self.repository = repository
        self.clock = clock
    }

    public func load() async {
        state.isRefreshing = state.load == .loaded
        publish()
        let result = await repository.friendsFlights()
        state.isRefreshing = false
        switch result {
        case .ok(let list):
            flights = list
            state.load = .loaded
        case .offline: if state.load != .loaded { state.load = .offline }
        case .notConfigured: state.load = .notConfigured
        case .refused, .failed: if state.load != .loaded { state.load = .failed }
        }
        render()
    }

    /// Re-renders the cards (countdown, progress) without a network call.
    public func tick() { render() }

    private func render() {
        let now = clock.now()
        state.rows = flights.map { FriendFlightRow(friendId: $0.friendId, friendName: $0.friendName, card: $0.flight.card(now: now)) }
        publish()
    }

    private func publish() { onChange?(state) }
}

// MARK: - share with friends (detail screen)

public struct ShareWithFriendsState: Equatable, Sendable {
    /// Nil: unknown yet, or the user does not track the flight (no switch).
    public var share: Bool?
    public var isSaving = false
    public var failed = false

    public init() {}
}

/// The "Friends can see this flight" switch on the detail screen (`user_flights.share_with_friends`).
@MainActor
public final class ShareWithFriendsModel {
    public private(set) var state = ShareWithFriendsState()
    public var onChange: ((ShareWithFriendsState) -> Void)?
    private let repository: FriendsRepository
    private let flightId: String

    public init(flightId: String, repository: FriendsRepository) {
        self.flightId = flightId
        self.repository = repository
    }

    public func load() async {
        if case .ok(let share) = await repository.shareWithFriends(flightId: flightId) {
            state.share = share
            publish()
        }
    }

    /// Optimistic; goes back when the server refuses.
    public func set(_ share: Bool) async {
        guard let before = state.share, before != share, !state.isSaving else { return }
        state.share = share
        state.isSaving = true
        state.failed = false
        publish()
        let result = await repository.setShareWithFriends(flightId: flightId, share: share)
        state.isSaving = false
        switch result {
        case .ok(let saved?): state.share = saved
        case .ok(.none): state.share = nil
        default:
            state.share = before
            state.failed = true
        }
        publish()
    }

    private func publish() { onChange?(state) }
}

// MARK: - friend alerts (push)

/// Friend alerts: a push about a friend's flight, sent to friends who do not track it themselves. Same
/// `kind = "alert"` message as a tracker alert, plus these data keys (notify.ts).
public enum FriendPushContract {
    /// `"friend"` for friend alerts; absent (tracker) otherwise.
    public static let keyRole = "role"
    public static let roleFriend = "friend"
    /// The friend's name as the alert text uses it ("Asha", or "Your friend" / "Aapka dost").
    public static let keyFriendName = "friend_name"
    /// The only event types that are critical for friends (`FRIEND_CRITICAL` in friend-alerts.ts).
    public static let criticalEventTypes: Set<String> = ["CANCELLED", "DIVERTED", "DIVERTED_DEST"]
}

/// True when the push data is a friend alert.
public func isFriendAlert(_ data: [String: String]) -> Bool {
    data[FriendPushContract.keyRole]?.trimmingCharacters(in: .whitespacesAndNewlines) == FriendPushContract.roleFriend
}

/// The channel for a friend alert: critical only for cancellation and diversion, quiet for the rest during quiet
/// hours, updates otherwise. A friend's delay must not ring like one's own gate change.
public func friendAlertChannelId(eventType: String, quiet: Bool) -> String {
    if FriendPushContract.criticalEventTypes.contains(eventType) { return PushContract.channelCritical }
    return quiet ? PushContract.channelQuiet : PushContract.channelUpdates
}
