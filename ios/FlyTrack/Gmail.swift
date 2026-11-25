import FlyTrackCore
import Foundation
import GoogleSignIn
import SwiftData
import UIKit

private let gmailScope = "https://www.googleapis.com/auth/gmail.readonly"

/// Gmail's REST API on URLSession. It sends only `Authorization: Bearer <Google token>` and never touches
/// Supabase, so Gmail import works without backend keys. Nothing from a reply is logged.
final class GmailAPIClient: GmailAPI {
    private let session: URLSession
    private let base = URL(string: "https://gmail.googleapis.com/gmail/v1/users/me/")!

    init(session: URLSession = .shared) { self.session = session }

    func profile(token: String) async throws -> GmailProfile {
        try await get(token, "profile")
    }

    func list(token: String, query: String, pageToken: String?) async throws -> GmailListResponse {
        var items = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "maxResults", value: "100")]
        if let pageToken { items.append(URLQueryItem(name: "pageToken", value: pageToken)) }
        return try await get(token, "messages", items)
    }

    func message(token: String, id: String) async throws -> GmailMessage {
        try await get(token, "messages/\(id)", [URLQueryItem(name: "format", value: "full")])
    }

    /// messages.attachments.get answers with a MessagePartBody, the same shape as `GmailBody`.
    func attachment(token: String, messageId: String, attachmentId: String) async throws -> Data {
        let body: GmailBody = try await get(token, "messages/\(messageId)/attachments/\(attachmentId)")
        guard let data = body.data.flatMap(decodeBase64URL) else { throw GmailFailure.other }
        return data
    }

    private func get<T: Decodable>(_ token: String, _ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        guard var components = URLComponents(url: base.appending(path: path), resolvingAgainstBaseURL: false) else {
            throw GmailFailure.other
        }
        components.queryItems = query.isEmpty ? nil : query
        // "+" reads as a space in a form-decoded query; encode it so a page token survives.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = components.url else { throw GmailFailure.other }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let reply: (Data, URLResponse)
        do {
            reply = try await session.data(for: request)
        } catch is URLError {
            throw GmailFailure.offline
        } catch {
            throw GmailFailure.other
        }
        if let failure = gmailFailure(status: (reply.1 as? HTTPURLResponse)?.statusCode ?? 0, body: reply.0) { throw failure }
        do {
            return try JSONDecoder().decode(T.self, from: reply.0)
        } catch {
            throw GmailFailure.other
        }
    }
}

/// A GoogleSignIn callback reduced to Sendable values inside the callback.
private enum SignedIn: Sendable {
    case user(token: String, email: String?, hasGmail: Bool)
    case refused(GmailTokenResult)

    init(_ user: GIDGoogleUser?, _ error: Error?) {
        if let user {
            self = .user(
                token: user.accessToken.tokenString,
                email: user.profile?.email,
                hasGmail: user.grantedScopes?.contains(gmailScope) ?? false
            )
            return
        }
        guard let error = error as NSError? else { self = .refused(.failed); return }
        let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError
        if error.domain == NSURLErrorDomain || underlying?.domain == NSURLErrorDomain {
            self = .refused(.offline)
        } else if error.domain == GIDSignInError.errorDomain || error.domain == "org.openid.appauth.oauth_token" {
            // No saved sign-in, a cancelled sheet, or a revoked or expired grant: the user has to act.
            self = .refused(.needsUser)
        } else {
            self = .refused(.failed)
        }
    }
}

/// Read-only Gmail access through the GoogleSignIn user. The app stores no token: GoogleSignIn keeps it in
/// the keychain. GoogleSignIn holds one current user, so linking a different Google account to HeyGate
/// replaces the Gmail user. The scope and email checks then return `.needsUser` ("Reconnect Gmail").
@MainActor
final class GmailAuth: GmailTokenProviding {
    private let settings: GmailSettingsStoring
    private let api: GmailAPI

    init(settings: GmailSettingsStoring, api: GmailAPI) {
        self.settings = settings
        self.api = api
    }

    /// Google's sign-in with the Gmail scope. On success stores the address and clears "needs reconnect".
    /// Cancelling, or unticking Gmail on the consent screen, returns `.needsUser`.
    func connect(presenting presenter: UIViewController? = nil) async -> GmailTokenResult {
        guard GoogleConfig.configure() else { return .notConfigured }
        guard let presenter = presenter ?? topController() else { return .failed }
        let signedIn: SignedIn = await withCheckedContinuation { continuation in
            GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: [gmailScope]) { result, error in
                continuation.resume(returning: SignedIn(result?.user, error))
            }
        }
        let (result, email) = await check(signedIn)
        guard case .granted = result, let email else { return result }
        if let previous = await settings.gmailAccountEmail(), previous.caseInsensitiveCompare(email) != .orderedSame {
            await settings.setGmailLastCheck(nil) // a different mailbox starts with the 180-day scan
        }
        await settings.setGmailAccountEmail(email)
        await settings.setGmailNeedsReconnect(false)
        return result
    }

    func silentToken() async -> GmailTokenResult {
        guard GoogleConfig.configure() else { return .notConfigured }
        guard let stored = await settings.gmailAccountEmail() else { return .needsUser }
        let (result, email) = await check(await restore())
        return GmailAccount.silentResult(result, restoredEmail: email, storedEmail: stored)
    }

    /// After a 401. ponytail: refreshTokensIfNeeded only refreshes a token near expiry, so a revoked but
    /// unexpired token gets a second 401, and the scanner then treats that as access lost, as the spec intends.
    func refresh() async -> GmailTokenResult { await silentToken() }

    /// Revokes the grant only while GoogleSignIn still holds `storedEmail`, the Gmail account the caller captured
    /// before it wiped local state (AppShell.clearGmail() or GmailImportModel.disconnect()). After a GoogleLinker
    /// switch, or offline, it does nothing (the user can remove the grant at myaccount.google.com).
    func disconnect(storedEmail stored: String) async {
        guard GoogleConfig.configure() else { return }
        guard case .user(_, let email?, _) = await restore(), email.caseInsensitiveCompare(stored) == .orderedSame else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            GIDSignIn.sharedInstance.disconnect { _ in continuation.resume() }
        }
    }

    private func restore() async -> SignedIn {
        await withCheckedContinuation { continuation in
            if let user = GIDSignIn.sharedInstance.currentUser {
                user.refreshTokensIfNeeded { user, error in continuation.resume(returning: SignedIn(user, error)) }
            } else {
                GIDSignIn.sharedInstance.restorePreviousSignIn { user, error in continuation.resume(returning: SignedIn(user, error)) }
            }
        }
    }

    /// `.granted` plus the account's address (from the ID token, else users.getProfile), or why not.
    private func check(_ signedIn: SignedIn) async -> (result: GmailTokenResult, email: String?) {
        switch signedIn {
        case .refused(let result):
            return (result, nil)
        case .user(let token, let email, let hasGmail):
            guard hasGmail else { return (.needsUser, nil) }
            if let email { return (.granted(token), email) }
            do {
                return (.granted(token), try await api.profile(token: token).emailAddress)
            } catch {
                return ((error as? GmailFailure) == .offline ? .offline : .failed, nil)
            }
        }
    }
}

/// Gmail suggestions and seen message ids in the app's SwiftData store.
/// ponytail: fetch-all then filter, like LocalCache. Both tables stay small (tens of suggestions, a few
/// thousand ids). Use #Predicate fetches if they ever grow.
@MainActor
final class SwiftDataGmailStore: GmailSuggestionStoring {
    private let context: ModelContext

    init(context: ModelContext) { self.context = context }

    func all() async -> [GmailSuggestion] { records().compactMap(\.suggestion) }

    func get(_ id: String) async -> GmailSuggestion? { records().first { $0.id == id }?.suggestion }

    /// The scanner inserts only new ids (GmailSuggestions.newOnly). The unique id makes a repeat an upsert.
    func insert(_ suggestions: [GmailSuggestion]) async {
        for suggestion in suggestions { context.insert(GmailSuggestionRecord(suggestion)) }
        try? context.save()
    }

    func setStatus(_ ids: [String], _ status: GmailSuggestionStatus) async {
        let wanted = Set(ids)
        for row in records() where wanted.contains(row.id) { row.status = status.rawValue }
        try? context.save()
    }

    func delete(_ ids: [String]) async {
        let wanted = Set(ids)
        for row in records() where wanted.contains(row.id) { context.delete(row) }
        try? context.save()
    }

    func seen(_ messageIds: [String]) async -> Set<String> {
        let stored = ((try? context.fetch(FetchDescriptor<GmailSeenMessageRecord>())) ?? []).map(\.messageId)
        return Set(messageIds).intersection(stored)
    }

    func markSeen(_ messageIds: [String]) async {
        let known = await seen(messageIds)
        for id in Set(messageIds).subtracting(known) { context.insert(GmailSeenMessageRecord(messageId: id)) }
        try? context.save()
    }

    func clear() async { LocalCache.wipeGmail(in: context) }

    private func records() -> [GmailSuggestionRecord] {
        (try? context.fetch(FetchDescriptor<GmailSuggestionRecord>())) ?? []
    }
}
