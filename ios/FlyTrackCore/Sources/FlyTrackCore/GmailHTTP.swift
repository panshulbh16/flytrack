import Foundation

/// A Gmail API reply as a scan failure: the HTTP status, and for 403 the error reasons. Nil for 2xx.
/// Same table as Android's KtorGmailApi and the spec's Errors section.
public func gmailFailure(status: Int, body: Data) -> GmailFailure? {
    switch status {
    case 200..<300:
        return nil
    case 401:
        return .unauthorized
    case 429, 500...:
        return .rateLimited
    case 403:
        let reasons = gmailErrorReasons(body)
        if !reasons.isDisjoint(with: ["rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded"]) { return .rateLimited }
        if !reasons.isDisjoint(with: ["insufficientPermissions", "forbidden"]) { return .accessLost }
        return .other
    default:
        return .other
    }
}

/// Google's error body: {"error": {"errors": [{"reason": "..."}]}}. Anything else has no reasons.
private struct GmailErrorBody: Decodable {
    struct Inner: Decodable { var errors: [Item]? }
    struct Item: Decodable { var reason: String? }
    var error: Inner?
}

private func gmailErrorReasons(_ body: Data) -> Set<String> {
    let decoded = try? JSONDecoder().decode(GmailErrorBody.self, from: body)
    return Set(decoded?.error?.errors?.compactMap(\.reason) ?? [])
}

public enum GmailAccount {
    /// The silent-token answer for the GoogleSignIn user that was just restored. GoogleSignIn keeps one user, so
    /// after GoogleLinker links another Google account the restored user is not the Gmail account. Its token must
    /// never read that mailbox: anything but the stored address (case-insensitive) is `.needsUser` ("Reconnect Gmail").
    public static func silentResult(_ restored: GmailTokenResult, restoredEmail: String?, storedEmail: String?) -> GmailTokenResult {
        guard case .granted = restored else { return restored }
        guard let restoredEmail, let storedEmail, restoredEmail.caseInsensitiveCompare(storedEmail) == .orderedSame else {
            return .needsUser
        }
        return restored
    }
}
