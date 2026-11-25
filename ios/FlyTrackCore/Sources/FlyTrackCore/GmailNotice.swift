import Foundation

/// The local-notification and background-check contract for Gmail suggestions. Same ids as Android's.
public enum GmailNotice {
    public static let category = "GMAIL_SUGGESTION"
    public static let actionTrack = "GMAIL_TRACK"
    public static let actionNotNow = "GMAIL_NOT_NOW"
    public static let keySuggestionId = "suggestion_id"
    /// Groups several finds from one check into one stack.
    public static let threadId = "gmail_suggestions"
    public static let backgroundTaskId = "app.heygate.gmail-check"
    public static let appOpenThrottleSeconds: Int64 = 3600

    /// "Track 6E 2341 on 3 Dec, DEL→BOM? Found in your Gmail.", or without a known route
    /// "Track 6E 2341 on 3 Dec? Found in your Gmail."
    public static func body(_ suggestion: GmailSuggestion) -> String {
        let day = FlightTimeFormat.dayMonth(suggestion.date)
        if let from = suggestion.fromIata, let to = suggestion.toIata, !from.isEmpty, !to.isEmpty {
            return "Track \(suggestion.flight) on \(day), \(from)→\(to)? Found in your Gmail."
        }
        return "Track \(suggestion.flight) on \(day)? Found in your Gmail."
    }

    /// "6E2341_2026-12-03". A notification's userInfo is checked with this before it is used.
    public static func isValidSuggestionId(_ id: String) -> Bool {
        id.wholeMatch(of: /[A-Z0-9]{3,8}_[0-9]{4}-[0-9]{2}-[0-9]{2}/) != nil
    }

    /// The app-open check runs at most once an hour. A clock set back since the last check also allows one.
    public static func appOpenCheckDue(lastCheck: Int64?, now: Int64) -> Bool {
        guard let lastCheck else { return true }
        return now < lastCheck || now - lastCheck >= appOpenThrottleSeconds
    }

    /// The automatic check (app open and BGTask) runs only in a build with the release switch on, for a connected
    /// account with Auto-check on and access not lost.
    public static func autoCheckEnabled(importEnabled: Bool, accountEmail: String?, autoCheck: Bool, needsReconnect: Bool) -> Bool {
        importEnabled && accountEmail != nil && autoCheck && !needsReconnect
    }

    /// A build with the release switch off forgets the Gmail state a build with it on left on the phone.
    public static func leftoversNeedWipe(importEnabled: Bool, accountEmail: String?) -> Bool {
        !importEnabled && accountEmail != nil
    }

    /// What Track opens: Add flight pre-filled, only while the suggestion is still pending. Nil when it was dismissed,
    /// added or deleted (Disconnect, past-date cleanup) since the notification was posted.
    public static func trackPrefill(_ suggestion: GmailSuggestion?) -> AddFlightPrefill? {
        guard let suggestion, suggestion.status == .pending else { return nil }
        return suggestion.addFlightPrefill
    }
}

extension GmailSuggestion {
    /// Add flight pre-filled from this suggestion. The notification's Track and the Found in Gmail card both open this.
    public var addFlightPrefill: AddFlightPrefill {
        AddFlightPrefill(flight: flight, date: date.iso, pnr: pnr, source: .gmail, gmailSuggestionId: id)
    }
}
