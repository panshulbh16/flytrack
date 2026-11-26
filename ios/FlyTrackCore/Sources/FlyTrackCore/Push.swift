import Foundation

/// The FCM contract shared with notify.ts. Channel ids, the click action and the data keys must stay identical.
public enum PushContract {
    public static let channelCritical = "flight_critical"
    public static let channelUpdates = "flight_updates"
    /// Info alerts during the user's quiet hours (P2 contract section 3). iOS: `interruption-level: passive`, no sound.
    public static let channelQuiet = "flight_quiet"
    public static let actionOpenFlight = "OPEN_FLIGHT"
    public static let keyKind = "kind"
    public static let keyFlightId = "flight_id"
    public static let keyEventId = "event_id"
    public static let keyEventType = "event_type"
    public static let keyTitle = "title"
    public static let keyBody = "body"
    public static let keyFlight = "flight"
    /// The airline's web check-in page on CHECKIN_OPEN / CHECKIN_CLOSING alerts (P1 contract section 3).
    public static let keyCheckinUrl = "checkin_url"
    /// Alerts only: "1" when the server sent this info alert silently (quiet hours); absent otherwise.
    public static let keyQuiet = "quiet"
    public static let quietTrue = "1"
    public static let kindAlert = "alert"
    public static let kindSync = "sync"
    /// Flights added from a forwarded email (P2 wave 2 section 2): "Added 2 flights from your email". `flight_id`
    /// (the first added flight) is optional.
    public static let kindImport = "import"
    public static let keyCount = "count"

    public static func notificationTag(flightId: String, group: String) -> String {
        "\(flightId):\(group)"
    }
}

public enum IncomingPush: Equatable, Sendable {
    case sync(flightId: String, flightJSON: String?)
    case alert(PushAlert)
    /// `kind = "import"`: flights were added from a forwarded email. The visible alert comes from APNs; the app
    /// refreshes its list. `count` is 0 when the server did not say.
    case imported(count: Int, flightId: String?)
    case ignored
}

public struct PushAlert: Equatable, Sendable {
    public var flightId: String
    public var eventId: String?
    public var eventType: AlertType?
    public var title: String
    public var body: String
    public var channelId: String
    public var tag: String
    public var notificationId: Int
    /// "time-sensitive" for flight_critical, "active" for flight_updates, "passive" for flight_quiet.
    public var interruptionLevel: String
    /// Safe https link to the airline's check-in page, on check-in reminders only.
    public var checkinUrl: String?
    /// The server sent this info alert silently (quiet hours): show it passive and without sound.
    public var quiet: Bool
    /// An alert about a friend's flight (`role = "friend"`, P2 wave 2 section 1); its text already names the friend.
    public var isFriend: Bool
    /// Friend alerts only: the friend's name (`friend_name`).
    public var friendName: String?

    public init(
        flightId: String,
        eventId: String?,
        eventType: AlertType?,
        title: String,
        body: String,
        channelId: String,
        tag: String,
        notificationId: Int,
        interruptionLevel: String,
        checkinUrl: String? = nil,
        quiet: Bool = false,
        isFriend: Bool = false,
        friendName: String? = nil
    ) {
        self.quiet = quiet
        self.isFriend = isFriend
        self.friendName = friendName
        self.flightId = flightId
        self.eventId = eventId
        self.eventType = eventType
        self.title = title
        self.body = body
        self.channelId = channelId
        self.tag = tag
        self.notificationId = notificationId
        self.interruptionLevel = interruptionLevel
        self.checkinUrl = checkinUrl
    }
}

/// The APNs half of an FCM message, when the sender included one. Absent on older Android-only payloads.
public struct ApnsEnvelope: Equatable, Sendable {
    public var collapseId: String?
    public var interruptionLevel: String?
    public var pushType: String?
    public var threadId: String?
    public var category: String?

    public init(collapseId: String? = nil, interruptionLevel: String? = nil, pushType: String? = nil, threadId: String? = nil, category: String? = nil) {
        self.collapseId = collapseId
        self.interruptionLevel = interruptionLevel
        self.pushType = pushType
        self.threadId = threadId
        self.category = category
    }
}

public func alertTagGroup(_ eventType: String) -> String {
    if eventType == AlertType.terminalChange.wire { return "terminal" }
    if eventType.hasPrefix("GATE") { return "gate" }
    if eventType.hasPrefix("BELT") { return "belt" }
    if eventType.hasPrefix("DELAY") || eventType == AlertType.inboundLate.wire || eventType == AlertType.scheduleChange.wire {
        return "delay"
    }
    // Replaces the "diverted, new destination not announced yet" alert (notify.ts tagGroup).
    if eventType == AlertType.divertedDest.wire { return "diverted" }
    return eventType.lowercased()
}

public func alertChannelId(_ type: AlertType?) -> String {
    if type == nil || type?.critical == true { return PushContract.channelCritical }
    return PushContract.channelUpdates
}

public func interruptionLevel(for channelId: String) -> String {
    switch channelId {
    case PushContract.channelCritical: return "time-sensitive"
    case PushContract.channelQuiet: return "passive"
    default: return "active"
    }
}

/// `alertChannelId` for an alert the server may have sent silently (P2 contract section 3). Critical alerts are
/// always loud, so a quiet flag on a critical type is ignored. The server only marks info alerts quiet, so an
/// unknown type that carries the flag goes quiet too.
public func alertChannelId(_ type: AlertType?, quiet: Bool) -> String {
    if !quiet { return alertChannelId(type) }
    if type?.critical == true { return PushContract.channelCritical }
    return PushContract.channelQuiet
}

/// Stable id for a tag. Same FNV-1a as the Android app, so a test can compare the two.
public func notificationId(for tag: String) -> Int {
    var hash: UInt32 = 0x811c9dc5
    for byte in tag.utf8 {
        hash ^= UInt32(byte)
        hash = hash &* 0x01000193
    }
    let id = Int(hash & 0x7fff_ffff)
    return id == 0 ? 1 : id
}

/// Turns an FCM data map into a typed message. An APNs envelope, when present, supplies the collapse id
/// and interruption level the server actually set; otherwise the Android channel rules are used.
public func parseIncomingPush(_ data: [String: String], apns: ApnsEnvelope? = nil) -> IncomingPush {
    let flightId = data[PushContract.keyFlightId]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if data[PushContract.keyKind] == PushContract.kindImport {
        let count = Int(data[PushContract.keyCount]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") ?? 0
        return .imported(count: max(0, count), flightId: isFlightUUID(flightId) ? flightId : nil)
    }
    if flightId.isEmpty { return .ignored }
    switch data[PushContract.keyKind] {
    case PushContract.kindSync:
        let json = data[PushContract.keyFlight].flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        return .sync(flightId: flightId, flightJSON: json)
    case PushContract.kindAlert:
        return alertOrIgnored(data, flightId: flightId, apns: apns)
    default:
        return .ignored
    }
}

/// Reads the APNs object notify.ts attaches beside the Android block. Unknown shapes return nil fields, not a throw.
public func apnsEnvelope(from message: [String: Any]) -> ApnsEnvelope? {
    guard let apns = message["apns"] as? [String: Any] else { return nil }
    let headers = apns["headers"] as? [String: Any]
    let payload = apns["payload"] as? [String: Any]
    let aps = payload?["aps"] as? [String: Any]
    return ApnsEnvelope(
        collapseId: JSON.string(headers?["apns-collapse-id"]),
        interruptionLevel: JSON.string(aps?["interruption-level"]),
        pushType: JSON.string(headers?["apns-push-type"]),
        threadId: JSON.string(aps?["thread-id"]),
        category: JSON.string(aps?["category"])
    )
}

public func parseIncomingPush(messageJSON: Data) -> IncomingPush {
    guard let object = JSON.object(from: messageJSON) else { return .ignored }
    let data = (object["data"] as? [String: Any])?.compactMapValues { JSON.string($0) } ?? [:]
    return parseIncomingPush(data, apns: apnsEnvelope(from: object))
}

private func alertOrIgnored(_ data: [String: String], flightId: String, apns: ApnsEnvelope?) -> IncomingPush {
    let title = data[PushContract.keyTitle]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let body = data[PushContract.keyBody]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if title.isEmpty && body.isEmpty { return .ignored }
    let wire = data[PushContract.keyEventType]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let type = AlertType.fromWire(wire)
    let fallbackGroup = wire.isEmpty ? "alert" : alertTagGroup(wire)
    let fallbackTag = PushContract.notificationTag(flightId: flightId, group: fallbackGroup)
    let tag = apns?.collapseId?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? fallbackTag
    let quietFlag = data[PushContract.keyQuiet]?.trimmingCharacters(in: .whitespacesAndNewlines) == PushContract.quietTrue
        || apns?.interruptionLevel == "passive"
    let friend = isFriendAlert(data)
    // Friend alerts: only cancellation and diversion are critical (friend-alerts.ts), so a friend's delay in quiet
    // hours is passive even though DELAY is critical for the tracker. A time-sensitive APNs level still wins.
    let channel: String
    if friend {
        let serverCritical = apns?.interruptionLevel == "time-sensitive" || apns?.interruptionLevel == "critical"
        channel = serverCritical ? PushContract.channelCritical : friendAlertChannelId(eventType: wire, quiet: quietFlag)
    } else {
        channel = channelId(interruptionLevel: apns?.interruptionLevel, type: type, quiet: quietFlag)
    }
    return .alert(PushAlert(
        flightId: flightId,
        eventId: data[PushContract.keyEventId].flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty },
        eventType: type,
        title: title,
        body: body,
        channelId: channel,
        tag: tag,
        notificationId: notificationId(for: tag),
        interruptionLevel: interruptionLevel(for: channel),
        checkinUrl: safeCheckinURL(data[PushContract.keyCheckinUrl])?.absoluteString,
        quiet: channel == PushContract.channelQuiet,
        isFriend: friend,
        friendName: friend ? data[FriendPushContract.keyFriendName]?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty : nil
    ))
}

private func channelId(interruptionLevel: String?, type: AlertType?, quiet: Bool) -> String {
    switch interruptionLevel {
    case "time-sensitive", "critical":
        return PushContract.channelCritical
    case "active":
        return quiet ? alertChannelId(type, quiet: true) : PushContract.channelUpdates
    case "passive":
        return alertChannelId(type, quiet: true)
    default:
        return alertChannelId(type, quiet: quiet)
    }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

/// Applies a compact sync payload on top of the cached flight. A payload older than the cache is ignored.
/// Fields the payload does not carry (airline, check-in, delay reason, Pro data) stay as they were.
public func mergeCompactFlight(json: String, into existing: Flight?) -> Flight? {
    guard let data = json.data(using: .utf8), let object = JSON.object(from: data) else { return nil }
    guard let id = JSON.string(object["id"]),
          let carrier = JSON.string(object["c"]),
          let number = JSON.string(object["n"]),
          let dateText = JSON.string(object["d"]),
          let date = LocalDate.parse(dateText),
          let dep = JSON.string(object["dep"]),
          let arr = JSON.string(object["arr"]) else { return nil }
    let updated = parseInstant(JSON.string(object["u"]))
    if let cached = existing?.updatedAt, let updated, updated < cached { return nil }
    let lat = JSON.double(object["lat"])
    let lon = JSON.double(object["lon"])
    let live: LivePosition?
    if let lat, let lon {
        live = LivePosition(
            lat: lat,
            lon: lon,
            altFt: JSON.int(object["alt"]),
            headingDeg: existing?.live?.headingDeg,
            speedKts: existing?.live?.speedKts,
            at: parseInstant(JSON.string(object["lu"]))
        )
    } else {
        live = nil
    }
    return Flight(
        id: id,
        carrierIata: carrier.trimmingCharacters(in: .whitespacesAndNewlines),
        flightNumber: number.trimmingCharacters(in: .whitespacesAndNewlines),
        flightDate: date,
        depIata: dep.trimmingCharacters(in: .whitespacesAndNewlines),
        arrIata: arr.trimmingCharacters(in: .whitespacesAndNewlines),
        airlineName: existing?.airlineName,
        status: FlightStatus.fromWire(JSON.string(object["st"])),
        schedDep: parseInstant(JSON.string(object["sd"])),
        estDep: parseInstant(JSON.string(object["ed"])),
        actDep: parseInstant(JSON.string(object["ad"])),
        depTerminal: JSON.string(object["dt"]),
        depGate: JSON.string(object["dg"]),
        depCheckin: existing?.depCheckin,
        schedArr: parseInstant(JSON.string(object["sa"])),
        estArr: parseInstant(JSON.string(object["ea"])),
        actArr: parseInstant(JSON.string(object["aa"])),
        arrTerminal: JSON.string(object["at"]),
        arrGate: JSON.string(object["ag"]),
        baggageBelt: JSON.string(object["bb"]),
        aircraftModel: JSON.string(object["ac"]),
        aircraftReg: JSON.string(object["reg"]),
        delayReason: existing?.delayReason,
        live: live,
        lastPolledAt: existing?.lastPolledAt,
        updatedAt: updated ?? existing?.updatedAt,
        depAirport: existing?.depAirport,
        arrAirport: existing?.arrAirport,
        inboundFlightId: existing?.inboundFlightId,
        prediction: existing?.prediction,
        hasProInsight: existing?.hasProInsight ?? false
    )
}
