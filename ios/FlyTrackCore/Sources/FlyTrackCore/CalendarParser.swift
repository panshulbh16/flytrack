import Foundation

// Swift port of HeyTurbulence src/calendar-parser.js (parseFlightEvent, splitLegs) for the P3 calendar import
// (docs/P3_CONTRACTS.md A3). It follows the Kotlin port line by line
// (android/app/src/main/kotlin/app/flytrack/feature/calendarimport/CalendarFlightParser.kt) and runs the same
// fixtures: Tests/FlyTrackCoreTests/Fixtures/calendar_events.json (a copy of
// android/app/src/test/resources/fixtures/calendar_events.json), whose expected values come from the JavaScript
// parser itself. Pure Foundation; NSRegularExpression (ICU) is used for the same pattern semantics as java.util.regex.
// All string positions are UTF-16 offsets, as in Kotlin and JavaScript.
//
// Differences from the JavaScript, all on purpose (same as Kotlin):
//  * review notes are typed `CalendarReviewIssue`s (the app words them);
//  * no redacted `report` and no display strings: the screen formats those;
//  * the source id prefix is a parameter (the device calendar is not Google's "primary" calendar).

/// One time of an event, in Google Calendar's shape. The EventKit reader fills `dateTime` with a UTC instant.
public struct CalendarEventTime: Hashable, Sendable {
    /// "2026-11-26T06:00:00+05:30", "2026-11-26T00:30:00Z", or a local "2026-11-26T06:00:00" with `timeZone`.
    public var dateTime: String?
    /// IANA zone of an offset-free `dateTime`.
    public var timeZone: String?
    /// "2026-11-26" for all-day events.
    public var date: String?

    public init(dateTime: String? = nil, timeZone: String? = nil, date: String? = nil) {
        self.dateTime = dateTime
        self.timeZone = timeZone
        self.date = date
    }
}

/// A calendar event as the parser sees it (Google Calendar's field names).
public struct CalendarEvent: Hashable, Sendable {
    public var id: String
    public var summary: String?
    public var description: String?
    public var location: String?
    public var start: CalendarEventTime?
    public var end: CalendarEventTime?
    /// "cancelled" drops the event.
    public var status: String?
    /// Google's eventType; birthday, workingLocation, outOfOffice and focusTime are never flights.
    public var eventType: String?

    public init(
        id: String, summary: String? = nil, description: String? = nil, location: String? = nil,
        start: CalendarEventTime? = nil, end: CalendarEventTime? = nil, status: String? = nil, eventType: String? = nil
    ) {
        self.id = id
        self.summary = summary
        self.description = description
        self.location = location
        self.start = start
        self.end = end
        self.status = status
        self.eventType = eventType
    }
}

/// Why a candidate needs a look before it is added (the JavaScript's review notes). Raw values match the fixtures.
public enum CalendarReviewIssue: String, Sendable, Equatable, CaseIterable {
    case noFrom = "NO_FROM"
    case noTo = "NO_TO"
    case sameAirport = "SAME_AIRPORT"
    /// All-day, or times without an offset or a valid zone.
    case untimed = "UNTIMED"
    /// The event names different routes.
    case conflict = "CONFLICT"
    /// The event covers several legs; see `CalendarCandidate.legs` / `splitLegs`.
    case multiLeg = "MULTI_LEG"
    /// A split leg whose own line states two clock times (shown for review, never filled in).
    case statedTimes = "STATED_TIMES"
}

/// Which times a split leg still lacks.
public enum CalendarMissingTimes: String, Sendable, Equatable {
    case takeoff = "TAKEOFF"
    case landing = "LANDING"
    case both = "BOTH"
}

/// What one leg's own line says: a flight number (when exactly one) and up to two clock times.
public struct CalendarLegDetail: Hashable, Sendable {
    public var number: String
    public var times: [String]

    public init(number: String, times: [String]) {
        self.number = number
        self.times = times
    }
}

/// An IATA pair of a multi-leg event.
public struct CalendarLeg: Hashable, Sendable {
    public var from: String
    public var to: String

    public init(from: String, to: String) {
        self.from = from
        self.to = to
    }
}

/// A flight-like event (or, after `splitLegs`, one leg of it).
public struct CalendarCandidate: Hashable, Sendable {
    public var sourceId: String
    public var from: CatalogAirport?
    public var to: CatalogAirport?
    /// IATA pairs of a multi-leg event, empty otherwise.
    public var legs: [CalendarLeg]
    public var legDetails: [CalendarLegDetail]
    public var startInstant: Date?
    public var endInstant: Date?
    /// "2026-11-26T06:00" at the departure airport, or "" when unknown.
    public var departLocal: String
    /// "2026-11-26T13:00" at the arrival airport, or "" when unknown.
    public var arriveLocal: String
    /// "SQ 509" (as written: leading zeros are kept), or "".
    public var number: String
    public var needsReview: Bool
    public var requiresCorrection: Bool
    public var issues: [CalendarReviewIssue]
    /// The start instant (ISO), the all-day date, or "".
    public var sortDate: String
    /// The event's all-day start date ("2026-11-26"), else nil.
    public var allDayDate: String?
    /// 1-based leg index after `splitLegs`; nil for a whole event.
    public var leg: Int?
    public var legCount: Int?
    public var missing: CalendarMissingTimes?
    /// Clock times the leg's own line states (`statedTimes` issue).
    public var statedTimes: [String]

    public init(
        sourceId: String, from: CatalogAirport?, to: CatalogAirport?, legs: [CalendarLeg], legDetails: [CalendarLegDetail],
        startInstant: Date?, endInstant: Date?, departLocal: String, arriveLocal: String, number: String,
        needsReview: Bool, requiresCorrection: Bool, issues: [CalendarReviewIssue], sortDate: String, allDayDate: String?,
        leg: Int? = nil, legCount: Int? = nil, missing: CalendarMissingTimes? = nil, statedTimes: [String] = []
    ) {
        self.sourceId = sourceId
        self.from = from
        self.to = to
        self.legs = legs
        self.legDetails = legDetails
        self.startInstant = startInstant
        self.endInstant = endInstant
        self.departLocal = departLocal
        self.arriveLocal = arriveLocal
        self.number = number
        self.needsReview = needsReview
        self.requiresCorrection = requiresCorrection
        self.issues = issues
        self.sortDate = sortDate
        self.allDayDate = allDayDate
        self.leg = leg
        self.legCount = legCount
        self.missing = missing
        self.statedTimes = statedTimes
    }
}

/// Parses calendar events against the bundled airport catalog. Immutable, so it can run off the main actor.
public struct CalendarFlightParser: Sendable {
    /// Source ids of device events start with this ("device:<event>:<start>").
    public static let deviceSourcePrefix = "device:"

    /// Match terms of one airport, as UTF-16 code units so `resolve` scans without converting each time.
    private struct Terms: Sendable {
        let airport: CatalogAirport
        let aliases: [[UInt16]]
        let name: [UInt16]?
        let city: [UInt16]?
    }

    private struct Route {
        let from: CatalogAirport?
        let to: CatalogAirport?
    }

    private let catalog: AirportList
    private let sourcePrefix: String
    private let terms: [Terms]

    public init(catalog: AirportList, sourcePrefix: String = CalendarFlightParser.deviceSourcePrefix) {
        self.catalog = catalog
        self.sourcePrefix = sourcePrefix
        terms = catalog.airports.map { a in
            let city = calendarNormalize(a.city)
            return Terms(
                airport: a,
                aliases: (CalendarParserData.aliases[a.iata] ?? []).map { Array(calendarNormalize($0).utf16) },
                name: a.name.utf16.count >= 10 ? Array(calendarPlain(a.name).utf16) : nil,
                city: a.city.utf16.count >= 4 && !CalendarParserData.ambiguous.contains(city) ? Array(city.utf16) : nil
            )
        }
    }

    private func byCode(_ code: String) -> CatalogAirport? { catalog[code] }

    private func knownCodes(_ text: String) -> [String] {
        var seen = Set<String>()
        var codes: [String] = []
        for code in CP.threeCaps.all(text).map(\.value) where byCode(code) != nil {
            if seen.insert(code).inserted { codes.append(code) }
        }
        return codes
    }

    private func resolve(_ segment: String) -> CatalogAirport? {
        let codes = knownCodes(segment)
        if codes.count == 1 { return byCode(codes[0]) }
        if codes.count > 1 { return nil }
        let normalized = calendarNormalize(segment)
        let text = UTF16Text(normalized)
        let specific = terms.filter { t in t.aliases.contains { text.hasWord($0) } }
        if specific.count == 1 { return specific[0].airport }
        if specific.count > 1 { return nil }
        let plainText = UTF16Text(calendarPlain(normalized))
        let named = terms.filter { t in t.name.map { plainText.hasWord($0) } ?? false }
        if named.count == 1 { return named[0].airport }
        if named.count > 1 { return nil }
        let matches = terms.filter { t in t.city.map { text.hasWord($0) } ?? false }
        return matches.count == 1 ? matches[0].airport : nil
    }

    /// Sequences of known codes such as "BLR → DXB → LHR" or "BLR-DEL-BLR".
    private func chains(_ text: String) -> [[CatalogAirport]] {
        var found: [[CatalogAirport]] = []
        for match in CP.chain.all(text) {
            let codes = CP.nonCaps.split(match.value).filter { !$0.isEmpty }
            let airports = codes.compactMap(byCode)
            if codes.count >= 2 && airports.count == codes.count { found.append(airports) }
        }
        return found
    }

    private func routes(_ text: String) -> [Route] {
        var found: [Route] = []
        for chain in chains(text) {
            for i in 0..<(chain.count - 1) { found.append(Route(from: chain[i], to: chain[i + 1])) }
        }
        let departure = CP.departureLabel.first(text)
        let arrival = CP.arrivalLabel.first(text)
        if departure != nil || arrival != nil {
            found.append(Route(from: departure.flatMap { resolve($0.group(1)) }, to: arrival.flatMap { resolve($0.group(1)) }))
        }
        var budget = CP.maxRouteLines
        for line in CP.lineSplit.split(text) {
            if line.utf16.count > CP.maxLine { continue }
            if budget <= 0 { break }
            guard let route = CP.routeLine1.first(line) ?? CP.routeLine2.first(line) ?? CP.routeLine3.first(line) else { continue }
            budget -= 1
            let first = route.group(1)
            if CP.toPrefix.contains(first.trimmingCharacters(in: .whitespacesAndNewlines)) {
                found.append(Route(from: nil, to: resolve(CP.toPrefixReplace.replaceFirst(first, with: ""))))
            } else {
                found.append(Route(from: resolve(first), to: resolve(route.group(2))))
            }
        }
        return found
    }

    private func flightNumber(summary: String, description: String) -> String {
        let texts = [summary] + description.components(separatedBy: "\n").filter { !CP.secretLine.contains($0) }
        for text in texts {
            if let m = CP.flightNumber.first(text) { return "\(m.group(1)) \(m.group(2))" }
        }
        return ""
    }

    private func legDetail(from: CatalogAirport, to: CatalogAirport, summary: String, description: String) -> CalendarLegDetail {
        let pair = CalendarRegex("\\b\(from.iata)\\b[^\\n]{0,40}?\\b\(to.iata)\\b")
        let lines = [summary] + description.components(separatedBy: "\n")
        guard let line = lines.first(where: { text in
            pair.contains(text) && !CP.secretLine.contains(text) && Set(knownCodes(text)).count == 2
        }) else { return CalendarLegDetail(number: "", times: []) }
        let numbers = CP.flightNumber.all(line)
        return CalendarLegDetail(
            number: numbers.count == 1 ? "\(numbers[0].group(1)) \(numbers[0].group(2))" : "",
            times: Array(CP.clock.all(line).map(\.value).prefix(2))
        )
    }

    /// One candidate for a flight-like event, or nil when the event is not about a flight.
    public func parse(_ event: CalendarEvent) -> CalendarCandidate? {
        if event.id.isEmpty || event.status == "cancelled" { return nil }
        if let type = event.eventType, CP.skippedTypes.contains(type) { return nil }
        let summary = calendarClean(event.summary)
        let description = calendarClean(event.description)
        let location = calendarClean(event.location)
        if CP.cancelled.contains(summary) { return nil }
        let summaryChains = chains(summary)
        if CP.reminder.contains(summary) && summaryChains.isEmpty { return nil }

        let summaryRoutes = routes(summary)
        let detailsRoutes = routes(description)
        // JavaScript's Map keeps the first insertion position and the last value.
        var completeKeys: [String] = []
        var completeByKey: [String: Route] = [:]
        for r in detailsRoutes + summaryRoutes {
            guard let from = r.from, let to = r.to else { continue }
            let key = "\(from.iata)|\(to.iata)"
            if completeByKey[key] == nil { completeKeys.append(key) }
            completeByKey[key] = r
        }
        let complete = completeKeys.compactMap { completeByKey[$0] }

        let context = "\(summary) \(description)"
        if !CP.flightContext.contains(context) && !context.contains("✈") && !CP.airlineNames.contains(context)
            && !CP.flightNumber.contains(summary) && summaryChains.isEmpty {
            return nil
        }

        // Legs: an explicit chain, or complete routes that connect in order. The first of the longest chains wins
        // (Kotlin's stable sortedByDescending).
        var chain: [CatalogAirport]?
        for candidate in summaryChains + chains(description) where candidate.count > (chain?.count ?? 0) {
            chain = candidate
        }
        var legs: [Route] = []
        if let chain, chain.count > 2 {
            legs = (1..<chain.count).map { Route(from: chain[$0 - 1], to: chain[$0]) }
        }
        if legs.isEmpty && complete.count > 1 {
            var linked = [complete[0]]
            for r in complete.dropFirst() {
                if r.from!.iata == linked.last!.to!.iata && r.to!.iata != linked.last!.from!.iata { linked.append(r) }
            }
            if linked.count == complete.count { legs = linked }
        }
        let multiLeg = legs.count > 1

        var from: CatalogAirport?
        var to: CatalogAirport?
        if multiLeg {
            from = legs.first?.from
            to = legs.last?.to
        } else {
            let r = complete.first
                ?? detailsRoutes.first { $0.from != nil || $0.to != nil }
                ?? summaryRoutes.first { $0.from != nil || $0.to != nil }
            from = r?.from
            to = r?.to
        }
        if to == nil, let m = CP.destination.first(summary) { to = resolve(m.group(1)) }
        if from == nil, let m = CP.origin.first(summary) { from = resolve(m.group(1)) }
        // A distinct, explicitly named airport location can fill a missing origin; it never overrides a route.
        if from == nil && (to != nil || CP.flightTo.contains(summary))
            && (CP.airportWord.contains(location) || CP.threeCaps.contains(location) || CP.locationLabel.contains(location)) {
            if let departure = resolve(CP.labelPrefix.replaceFirst(location, with: "")), departure.iata != to?.iata {
                from = departure
            }
        }

        let conflict = !multiLeg && complete.contains { r in
            r.from!.iata != complete[0].from!.iata || r.to!.iata != complete[0].to!.iata
        }
        let startInstant = calendarInstant(event.start)
        let endInstant = calendarInstant(event.end)
        let timed = startInstant != nil && endInstant != nil

        var issues: [CalendarReviewIssue] = []
        if from == nil { issues.append(.noFrom) }
        if to == nil { issues.append(.noTo) }
        if let from, let to, from.iata == to.iata, !multiLeg { issues.append(.sameAirport) }
        if !timed { issues.append(.untimed) }
        if conflict { issues.append(.conflict) }
        if multiLeg { issues.append(.multiLeg) }
        let sameAirport = from != nil && to != nil && from!.iata == to!.iata
        let allDayDate = event.start?.date.flatMap { CP.isoDate.matchesWhole($0) ? $0 : nil }
        let sortDate = startInstant.map(calendarInstantText) ?? allDayDate ?? ""
        var departLocal = ""
        var arriveLocal = ""
        if timed, let startInstant, let from { departLocal = calendarLocalText(startInstant, tz: from.tz) }
        if timed, let endInstant, let to { arriveLocal = calendarLocalText(endInstant, tz: to.tz) }
        return CalendarCandidate(
            sourceId: "\(sourcePrefix)\(event.id)",
            from: from,
            to: to,
            legs: legs.map { CalendarLeg(from: $0.from!.iata, to: $0.to!.iata) },
            legDetails: multiLeg ? legs.map { legDetail(from: $0.from!, to: $0.to!, summary: summary, description: description) } : [],
            startInstant: startInstant,
            endInstant: endInstant,
            departLocal: departLocal,
            arriveLocal: arriveLocal,
            number: flightNumber(summary: summary, description: description),
            needsReview: from == nil || to == nil || !timed || conflict || multiLeg || sameAirport,
            requiresCorrection: conflict || multiLeg || sameAirport,
            issues: issues,
            sortDate: sortDate,
            allDayDate: allDayDate
        )
    }

    /// A multi-leg candidate becomes one candidate per leg, so each can be reviewed and added on its own. Only stated
    /// times are kept: the event start is the first takeoff and the event end the last landing.
    public func splitLegs(_ candidate: CalendarCandidate?) -> [CalendarCandidate] {
        guard let candidate else { return [] }
        let count = candidate.legs.count
        if count < 2 { return [candidate] }
        return candidate.legs.enumerated().map { i, leg in
            let first = i == 0
            let last = i == count - 1
            let detail = i < candidate.legDetails.count ? candidate.legDetails[i] : CalendarLegDetail(number: "", times: [])
            let departLocal = first ? candidate.departLocal : ""
            let arriveLocal = last ? candidate.arriveLocal : ""
            let missing: CalendarMissingTimes
            if !departLocal.isEmpty && arriveLocal.isEmpty {
                missing = .landing
            } else if !arriveLocal.isEmpty && departLocal.isEmpty {
                missing = .takeoff
            } else {
                missing = .both
            }
            let stated = detail.times.count == 2 ? detail.times : []
            var copy = candidate
            copy.sourceId = "\(candidate.sourceId):leg\(i + 1)"
            copy.from = byCode(leg.from)
            copy.to = byCode(leg.to)
            copy.startInstant = first ? candidate.startInstant : nil
            copy.endInstant = last ? candidate.endInstant : nil
            copy.departLocal = departLocal
            copy.arriveLocal = arriveLocal
            copy.number = detail.number
            copy.needsReview = true
            copy.requiresCorrection = false
            copy.issues = stated.isEmpty ? [] : [.statedTimes]
            copy.leg = i + 1
            copy.legCount = count
            copy.missing = missing
            copy.statedTimes = stated
            return copy
        }
    }

    /// `parse` then `splitLegs`: what the import screen lists for one event.
    public func candidates(_ event: CalendarEvent) -> [CalendarCandidate] { splitLegs(parse(event)) }
}

// MARK: - text helpers (internal so tests can reach them)

/// The JavaScript `clean`: HTML to text, entities decoded, capped to 20,000 UTF-16 units before any regex work
/// (calendar text comes from anyone who can send an invite).
func calendarClean(_ s: String?) -> String {
    var text = s ?? ""
    let ns = text as NSString
    if ns.length > CP.maxText { text = ns.substring(to: CP.maxText) }
    text = CP.scriptStyle.replaceAll(text, with: " ")
    text = CP.blockBreak.replaceAll(text, with: "\n")
    text = CP.tag.replaceAll(text, with: " ")
    text = CP.entity.replaceAll(text) { CP.entities[$0.lowercased()] ?? $0 }
    text = CP.plane.replaceAll(text, with: " ✈ ")
    text = CP.spaces.replaceAll(text, with: " ")
    return text.trimmingCharacters(in: .whitespacesAndNewlines)
}

/// NFKD, combining marks U+0300–U+036F removed, lower-cased.
func calendarNormalize(_ s: String) -> String {
    CP.combining.replaceAll(s.decomposedStringWithCompatibilityMapping, with: "").lowercased()
}

/// `calendarNormalize`, then every run of characters other than a–z and 0–9 becomes one space.
func calendarPlain(_ s: String) -> String {
    CP.multiSpace.replaceAll(CP.nonAlnum.replaceAll(calendarNormalize(s), with: " "), with: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

/// `term` occurs in `text` without a letter a–z directly before or after it.
func calendarBoundary(_ text: String, _ term: String) -> Bool {
    utf16Boundary(Array(text.utf16), Array(term.utf16))
}

/// A text prepared for many `calendarBoundary` lookups: its UTF-16 units and the set of adjacent unit pairs, so the
/// thousands of catalog terms that cannot occur are skipped with one hash lookup each.
private struct UTF16Text {
    let units: [UInt16]
    private let pairs: Set<UInt32>

    init(_ text: String) {
        units = Array(text.utf16)
        var set = Set<UInt32>()
        if units.count > 1 {
            for i in 0..<(units.count - 1) { set.insert(UInt32(units[i]) << 16 | UInt32(units[i + 1])) }
        }
        pairs = set
    }

    func hasWord(_ term: [UInt16]) -> Bool {
        if term.count >= 2 && !pairs.contains(UInt32(term[0]) << 16 | UInt32(term[1])) { return false }
        return utf16Boundary(units, term)
    }
}

/// `calendarBoundary` on UTF-16 code units (Kotlin's `String.indexOf` loop).
private func utf16Boundary(_ t: [UInt16], _ p: [UInt16]) -> Bool {
    if p.isEmpty || p.count > t.count { return false }
    @inline(__always) func isLetter(_ unit: UInt16) -> Bool { unit >= 0x61 && unit <= 0x7A }
    let first = p[0]
    var i = 0
    let last = t.count - p.count
    while i <= last {
        if t[i] == first {
            var k = 1
            while k < p.count && t[i + k] == p[k] { k += 1 }
            if k == p.count {
                let before = i == 0 || !isLetter(t[i - 1])
                let end = i + p.count
                let after = end == t.count || !isLetter(t[end])
                if before && after { return true }
            }
        }
        i += 1
    }
    return false
}

/// The event time as an instant: an offset date-time, or a local one in a valid zone. Nil otherwise.
func calendarInstant(_ part: CalendarEventTime?) -> Date? {
    guard let value = part?.dateTime else { return nil }
    if CP.offsetSuffix.contains(value), let instant = parseOffsetDateTime(value) { return instant }
    guard let zone = part?.timeZone, !zone.isEmpty else { return nil }
    let ns = value as NSString
    return localToInstant(ns.length > 16 ? ns.substring(to: 16) : value, tz: zone)
}

/// "2026-11-26T00:30:00Z" (whole seconds; fractions only when present), like Kotlin's `Instant.toString()`.
func calendarInstantText(_ instant: Date) -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second, .nanosecond], from: instant)
    let base = String(format: "%04d-%02d-%02dT%02d:%02d:%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0, c.second ?? 0)
    let millis = Int((Double(c.nanosecond ?? 0) / 1_000_000).rounded())
    return millis > 0 && millis < 1000 ? base + String(format: ".%03dZ", millis) : base + "Z"
}

/// HeyTurbulence `instantToLocal`: "2026-11-26T06:00" in `tz`; "" for an unknown zone.
func calendarLocalText(_ instant: Date, tz: String) -> String {
    guard let zone = TimeZone(identifier: tz) else { return "" }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: instant)
    return String(format: "%04d-%02d-%02dT%02d:%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0)
}

/// ISO-8601 with an offset ("…+05:30" or "…Z"), seconds and fractions optional. Nil for a malformed value or a date
/// that does not exist (`OffsetDateTime.parse` rejects those too).
private func parseOffsetDateTime(_ text: String) -> Date? {
    guard let m = CP.offsetDateTime.first(text), m.range.length == (text as NSString).length else { return nil }
    guard let year = Int(m.group(1)), let month = Int(m.group(2)), let day = Int(m.group(3)),
          let hour = Int(m.group(4)), let minute = Int(m.group(5)) else { return nil }
    let second = Int(m.group(6)) ?? 0
    let fraction = m.group(7)
    let zone = m.group(8)
    guard (1...12).contains(month), (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second),
          LocalDate.valid(year: year, month: month, day: day) != nil else { return nil }
    var offset = 0
    if zone != "Z" {
        let sign = zone.hasPrefix("-") ? -1 : 1
        let parts = zone.dropFirst().split(separator: ":")
        guard parts.count == 2, let h = Int(parts[0]), let mm = Int(parts[1]), h <= 18, mm <= 59 else { return nil }
        offset = sign * (h * 3600 + mm * 60)
    }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    guard let utcWall = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)) else {
        return nil
    }
    var nanos = 0.0
    if !fraction.isEmpty, let digits = Double("0." + fraction) { nanos = digits }
    return utcWall.addingTimeInterval(nanos - Double(offset))
}

/// HeyTurbulence `localToUTC`: nil for a malformed value, an unknown zone, or a wall time that does not exist (DST gap).
private func localToInstant(_ local: String, tz: String) -> Date? {
    guard CP.localMinutes.matchesWhole(local), let zone = TimeZone(identifier: tz) else { return nil }
    let ns = local as NSString
    guard let year = Int(ns.substring(with: NSRange(location: 0, length: 4))),
          let month = Int(ns.substring(with: NSRange(location: 5, length: 2))),
          let day = Int(ns.substring(with: NSRange(location: 8, length: 2))),
          let hour = Int(ns.substring(with: NSRange(location: 11, length: 2))),
          let minute = Int(ns.substring(with: NSRange(location: 14, length: 2))),
          (1...12).contains(month), (0...23).contains(hour), (0...59).contains(minute),
          LocalDate.valid(year: year, month: month, day: day) != nil else { return nil }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    guard let date = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)) else { return nil }
    let back = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    guard back.year == year, back.month == month, back.day == day, back.hour == hour, back.minute == minute else { return nil }
    return date
}

// MARK: - regular expressions

/// A compiled NSRegularExpression with the few operations the parser needs (UTF-16 ranges, like java.util.regex).
struct CalendarRegex: Sendable {
    struct Match {
        let ns: NSString
        let result: NSTextCheckingResult
        var range: NSRange { result.range }
        var value: String { ns.substring(with: result.range) }
        /// The group's text, or "" when it did not take part (Kotlin's `groupValues`).
        func group(_ index: Int) -> String {
            guard index < result.numberOfRanges else { return "" }
            let r = result.range(at: index)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    let expression: NSRegularExpression

    init(_ pattern: String, ignoreCase: Bool = false) {
        do {
            expression = try NSRegularExpression(pattern: pattern, options: ignoreCase ? [.caseInsensitive] : [])
        } catch {
            preconditionFailure("bad calendar pattern \(pattern): \(error)")
        }
    }

    private func whole(_ ns: NSString) -> NSRange { NSRange(location: 0, length: ns.length) }

    func first(_ text: String) -> Match? {
        let ns = text as NSString
        return expression.firstMatch(in: text, options: [], range: whole(ns)).map { Match(ns: ns, result: $0) }
    }

    func all(_ text: String) -> [Match] {
        let ns = text as NSString
        return expression.matches(in: text, options: [], range: whole(ns)).map { Match(ns: ns, result: $0) }
    }

    func contains(_ text: String) -> Bool { first(text) != nil }

    /// The whole text matches (Kotlin's `Regex.matches`).
    func matchesWhole(_ text: String) -> Bool {
        let ns = text as NSString
        guard let m = expression.firstMatch(in: text, options: [.anchored], range: whole(ns)) else { return false }
        return m.range.location == 0 && m.range.length == ns.length
    }

    func replaceAll(_ text: String, with template: String) -> String {
        let ns = text as NSString
        return expression.stringByReplacingMatches(
            in: text, options: [], range: whole(ns), withTemplate: NSRegularExpression.escapedTemplate(for: template)
        )
    }

    func replaceAll(_ text: String, _ transform: (String) -> String) -> String {
        let ns = text as NSString
        var out = ""
        var cursor = 0
        for m in expression.matches(in: text, options: [], range: whole(ns)) {
            out += ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
            out += transform(ns.substring(with: m.range))
            cursor = m.range.location + m.range.length
        }
        out += ns.substring(from: cursor)
        return out
    }

    func replaceFirst(_ text: String, with replacement: String) -> String {
        guard let m = first(text) else { return text }
        return m.ns.replacingCharacters(in: m.range, with: replacement)
    }

    /// Kotlin's `CharSequence.split(Regex)`: every piece, empty ones included.
    func split(_ text: String) -> [String] {
        let ns = text as NSString
        var parts: [String] = []
        var cursor = 0
        for m in expression.matches(in: text, options: [], range: whole(ns)) {
            parts.append(ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor)))
            cursor = m.range.location + m.range.length
        }
        parts.append(ns.substring(from: cursor))
        return parts
    }
}

/// The parser's patterns, compiled once (the Kotlin companion object).
enum CP {
    static let maxText = 20_000
    static let maxLine = 400
    static let maxRouteLines = 100

    static let entities: [String: String] = [
        "&nbsp;": " ", "&#160;": " ", "&amp;": "&", "&quot;": "\"", "&#39;": "'", "&apos;": "'",
        "&lt;": "<", "&gt;": ">", "&rarr;": " → ", "&#8594;": " → ", "&#x2192;": " → ",
        "&ndash;": " – ", "&#8211;": " – ", "&mdash;": " — ", "&#8212;": " — ",
        "&middot;": " · ", "&#183;": " · ", "&bull;": " · ", "&#8226;": " · ",
    ]

    static let scriptStyle = CalendarRegex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", ignoreCase: true)
    static let blockBreak = CalendarRegex("<br\\s*/?\\s*>|</(p|div|tr|li|h[1-6])>", ignoreCase: true)
    static let tag = CalendarRegex("<[^>]*>")
    static let entity = CalendarRegex("&[a-z]+;|&#x?[0-9a-f]+;", ignoreCase: true)
    static let plane = CalendarRegex("[\\u2708\\uFE0F]")
    static let spaces = CalendarRegex("[ \\t]+")
    static let combining = CalendarRegex("[\\u0300-\\u036f]")
    static let nonAlnum = CalendarRegex("[^a-z0-9]+")
    static let multiSpace = CalendarRegex("\\s+")

    static let airlineNames = CalendarRegex(
        "\\b(indigo|air india|vistara|akasa|spicejet|air ?asia|alliance air|star air|emirates|etihad|qatar airways|flydubai|"
            + "air arabia|oman air|saudia|gulf air|kuwait airways|singapore airlines|scoot|malaysia airlines|thai airways|cathay|"
            + "japan airlines|ana\\b|korean air|asiana|china eastern|china southern|air china|turkish airlines|lufthansa|"
            + "swiss international|austrian airlines|klm|air france|british airways|virgin atlantic|aer lingus|iberia|vueling|"
            + "ryanair|easyjet|tap air|ita airways|finnair|norwegian|lot polish|aeroflot|united airlines|american airlines|delta|"
            + "southwest|jetblue|alaska airlines|air canada|westjet|aeromexico|latam|avianca|copa|qantas|jetstar|virgin australia|"
            + "air new zealand|fiji airways|ethiopian|kenya airways|egyptair|south african airways|royal jordanian|srilankan|"
            + "nepal airlines|biman|batik|garuda|lion air|vietjet|vietnam airlines|philippine airlines|cebu pacific|hk express|"
            + "eva air|china airlines|peach|jeju air|air busan)\\b",
        ignoreCase: true
    )

    static let airlineCodes = [
        "6E", "AI", "UK", "SG", "IX", "QP", "I5", "G8", "S5", "9I", "2T", "SQ",
        "TR", "EK", "FZ", "EY", "QR", "WY", "G9", "SV", "GF", "KU", "RJ", "MS",
        "ET", "KQ", "SA", "UL", "RA", "BG", "BS", "MH", "AK", "D7", "OD", "GA",
        "QZ", "JT", "TG", "FD", "DD", "VZ", "VN", "VJ", "PR", "5J", "CX", "UO",
        "HX", "CI", "BR", "JL", "NH", "MM", "GK", "KE", "OZ", "7C", "TW", "LJ",
        "BX", "CA", "MU", "CZ", "HU", "3U", "TK", "PC", "LH", "LX", "OS", "SN",
        "EW", "AF", "KL", "BA", "VS", "EI", "IB", "VY", "FR", "U2", "TP", "AZ",
        "SK", "AY", "DY", "LO", "W6", "SU", "A3", "UA", "AA", "DL", "WN", "B6",
        "AS", "NK", "F9", "AC", "WS", "AM", "LA", "AV", "CM", "QF", "JQ", "VA",
        "NZ", "FJ", "TN",
    ]

    static let flightNumber = CalendarRegex("(?:^|[^A-Z0-9])(\(airlineCodes.joined(separator: "|")))[ -]?(\\d{1,4})(?![0-9A-Z])")

    static let secretLine = CalendarRegex(
        "\\b(pnr|booking|reference|confirmation|record locator|locator|ticket|e-?ticket|passport|itinerary (?:no|number|id))\\b",
        ignoreCase: true
    )
    static let codeSeparator = "(?:→|->|–|—|>|\\bto\\b|\\bTO\\b|-|/)"
    static let chain = CalendarRegex("\\b[A-Z]{3}(?:\\s*\(codeSeparator)\\s*[A-Z]{3}\\b)+")
    static let nonCaps = CalendarRegex("[^A-Z]+")
    static let threeCaps = CalendarRegex("\\b[A-Z]{3}\\b")

    static let departureLabel = CalendarRegex(
        "(?:departure(?:\\s+(?:airport|city))?|depart(?:ing|s)?(?:\\s+from)?|origin|from)\\s*:\\s*([^\\n;|]+)",
        ignoreCase: true
    )
    static let arrivalLabel = CalendarRegex(
        "(?:arrival(?:\\s+(?:airport|city))?|arriv(?:al|e|es|ing)?(?:\\s+(?:at|in))?|destination|to)\\s*:\\s*([^\\n;|]+)",
        ignoreCase: true
    )
    static let lineSplit = CalendarRegex("[\\n;|]")

    // JavaScript `$` without the m flag is the end of the input: \z here.
    static let routeLine1 = CalendarRegex("^(?:✈\\s*)?(?:flight\\s+)?(?:from\\s+)?(.+?)\\s+(?:to|→|->|–|—|/|-|>)\\s+(.+)\\z", ignoreCase: true)
    static let routeLine2 = CalendarRegex("^(?:✈\\s*)?(?:flight\\s+)?(?:from\\s+)?(.+?)\\s*(?:→|->)\\s*(.+)\\z", ignoreCase: true)
    static let routeLine3 = CalendarRegex("([a-z][a-z .']*[a-z])\\s?[-–]\\s?([a-z][a-z .']*[a-z])", ignoreCase: true)
    static let toPrefix = CalendarRegex("^to\\s", ignoreCase: true)
    static let toPrefixReplace = CalendarRegex("^to\\s+", ignoreCase: true)

    static let clock = CalendarRegex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    static let skippedTypes: Set<String> = ["birthday", "workingLocation", "outOfOffice", "focusTime"]
    static let cancelled = CalendarRegex("\\b(cancelled|canceled)\\b", ignoreCase: true)
    static let reminder = CalendarRegex("\\b(check[ -]?in|boarding pass|baggage|lounge)\\b", ignoreCase: true)
    static let flightContext = CalendarRegex("\\b(flight|flights|flying|fly|airways|airlines?|boarding|✈)\\b", ignoreCase: true)
    static let destination = CalendarRegex("\\b(?:flight|flying|fly)\\s+to\\s+(.+)", ignoreCase: true)
    static let origin = CalendarRegex("\\b(?:flight|flying|fly)\\s+from\\s+(.+?)(?:\\s+to\\s+.*)?\\z", ignoreCase: true)
    static let flightTo = CalendarRegex("\\b(?:flight|flying|fly)\\s+to\\b", ignoreCase: true)
    static let airportWord = CalendarRegex("\\bairport\\b", ignoreCase: true)
    static let locationLabel = CalendarRegex("^\\s*(?:depart(?:ure)?|from)\\s*:", ignoreCase: true)
    static let labelPrefix = CalendarRegex("^[^:]+:")
    static let isoDate = CalendarRegex("^\\d{4}-\\d{2}-\\d{2}$")
    static let offsetSuffix = CalendarRegex("(?:Z|[+-]\\d{2}:\\d{2})$")
    static let offsetDateTime = CalendarRegex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|[+-]\\d{2}:\\d{2})$")
    static let localMinutes = CalendarRegex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")
}

/// Airport names and city aliases that are safe to map to one catalog airport, and cities where guessing is unsafe.
enum CalendarParserData {
    static let aliases: [String: [String]] = [
        "BLR": ["Bangalore", "Bengaluru", "Kempegowda"],
        "BOM": ["Bombay", "Chhatrapati Shivaji"],
        "DEL": ["Delhi", "New Delhi", "Indira Gandhi", "IGI"],
        "MAA": ["Madras"],
        "CCU": ["Calcutta", "Netaji Subhas", "Netaji Subhash"],
        "HYD": ["Rajiv Gandhi", "Shamshabad"],
        "COK": ["Cochin", "Nedumbassery"],
        "GOI": ["Dabolim"],
        "GOX": ["Mopa", "Manohar International"],
        "AMD": ["Sardar Vallabhbhai"],
        "LKO": ["Chaudhary Charan Singh"],
        "GAU": ["Lokpriya Gopinath"],
        "BBI": ["Biju Patnaik"],
        "PAT": ["Jay Prakash Narayan", "Jayaprakash Narayan"],
        "IDR": ["Devi Ahilya"],
        "NAG": ["Babasaheb Ambedkar"],
        "VNS": ["Lal Bahadur Shastri", "Benares", "Banaras"],
        "BEK": ["Bareli"],
        "AYJ": ["Maharishi Valmiki", "Faizabad"],
        "DXN": ["Jewar", "Noida International"],
        "IXE": ["Mangalore"],
        "TRV": ["Trivandrum"],
        "VTZ": ["Vizag", "Visakhapatnam"],
        "CCJ": ["Calicut"],
        "TRZ": ["Trichy", "Tiruchirapalli"],
        "BDQ": ["Baroda"],
        "HBX": ["Hubli"],
        "IXG": ["Belgaum"],
        "MYQ": ["Mysore"],
        "IXU": ["Chhatrapati Sambhajinagar"],
        "IXD": ["Allahabad"],
        "KUU": ["Bhuntar"],
        "DHM": ["Gaggal", "Kangra"],
        "AJL": ["Lengpui"],
        "DPS": ["Denpasar", "Ngurah Rai"],
        "CGK": ["Soekarno", "Soekarno-Hatta"],
        "KUL": ["KLIA"],
        "MNL": ["Ninoy Aquino"],
        "SGN": ["Tan Son Nhat", "Saigon"],
        "HAN": ["Noi Bai"],
        "BKK": ["Suvarnabhumi"],
        "DMK": ["Don Mueang", "Don Muang"],
        "MLE": ["Velana"],
        "CMB": ["Bandaranaike"],
        "KTM": ["Tribhuvan"],
        "DAC": ["Hazrat Shahjalal"],
        "KHI": ["Jinnah"],
        "LHE": ["Allama Iqbal"],
        "LHR": ["Heathrow", "London Heathrow"],
        "LGW": ["Gatwick"],
        "STN": ["Stansted"],
        "CDG": ["Charles de Gaulle", "Roissy"],
        "ORY": ["Orly"],
        "AMS": ["Schiphol"],
        "FCO": ["Fiumicino", "Leonardo da Vinci"],
        "MXP": ["Malpensa"],
        "MAD": ["Barajas"],
        "BCN": ["El Prat"],
        "SVO": ["Sheremetyevo"],
        "HND": ["Haneda"],
        "NRT": ["Narita"],
        "KIX": ["Kansai"],
        "ICN": ["Incheon"],
        "GMP": ["Gimpo"],
        "PVG": ["Pudong"],
        "SHA": ["Hongqiao"],
        "PKX": ["Daxing"],
        "JFK": ["John F Kennedy", "John F. Kennedy", "Kennedy"],
        "LGA": ["LaGuardia", "La Guardia"],
        "EWR": ["Newark Liberty"],
        "BOS": ["Logan"],
        "IAD": ["Dulles"],
        "DCA": ["Reagan National"],
        "ATL": ["Hartsfield"],
        "ORD": ["O'Hare", "OHare"],
        "SEA": ["Sea-Tac", "SeaTac"],
        "YYZ": ["Pearson"],
        "YUL": ["Trudeau"],
        "SYD": ["Kingsford Smith"],
        "MEL": ["Tullamarine"],
        "NBO": ["Jomo Kenyatta"],
        "ADD": ["Bole"],
        "JNB": ["O. R. Tambo", "OR Tambo", "O.R. Tambo"],
        "GRU": ["Guarulhos"],
        "GIG": ["Galeão", "Galeao"],
        "EZE": ["Ezeiza"],
        "MEX": ["Benito Juárez", "Benito Juarez"],
        "LIM": ["Jorge Chávez", "Jorge Chavez"],
        "BOG": ["El Dorado"],
        "PTY": ["Tocumen"],
        "SIN": ["Changi"],
        "DXB": ["Dubai International"],
        "DOH": ["Hamad"],
        "AUH": ["Zayed International"],
        "RUH": ["King Khalid"],
        "JED": ["King Abdulaziz"],
        "SAW": ["Sabiha", "Sabiha Gökçen", "Sabiha Gokcen"],
    ]

    static let ambiguous: Set<String> = [
        "london", "paris", "tokyo", "osaka", "new york", "washington", "chicago", "houston", "dallas", "seoul",
        "bangkok", "istanbul", "rome", "milan", "moscow", "goa", "melbourne", "taipei", "toronto", "mumbai",
        "shanghai", "beijing", "buenos aires", "sao paulo", "rio de janeiro",
    ]
}
