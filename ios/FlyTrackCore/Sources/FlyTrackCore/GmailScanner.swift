import Foundation

/// Runs one Gmail scan end to end and writes what it finds. Used by the Gmail screen, the app-open check and the BGTask.
/// Callers run `cleanup()` first and remove the notifications of the ids it returns.
@MainActor
public final class GmailScanner {
    static let maxMessages = 50
    static let maxPdfBytes: Int64 = 5_000_000

    private let api: GmailAPI
    private let tokens: GmailTokenProviding
    private let store: GmailSuggestionStoring
    private let settings: GmailSettingsStoring
    private let tracked: TrackedKeysProviding
    private let pdf: PDFTextReading
    private let now: () -> Int64
    private let today: () -> LocalDate

    /// `now` is epoch milliseconds; `today` is the phone's local date.
    public init(
        api: GmailAPI, tokens: GmailTokenProviding, store: GmailSuggestionStoring, settings: GmailSettingsStoring,
        tracked: TrackedKeysProviding, pdf: PDFTextReading, now: @escaping () -> Int64, today: @escaping () -> LocalDate
    ) {
        self.api = api
        self.tokens = tokens
        self.store = store
        self.settings = settings
        self.tracked = tracked
        self.pdf = pdf
        self.now = now
        self.today = today
    }

    /// Why a scan stopped before the listing was read to the end.
    private enum Stop: Error {
        case partial, offline, accessLost

        var result: GmailScanResult {
            switch self {
            case .partial: return .partial
            case .offline: return .offline
            case .accessLost: return .needsReconnect
            }
        }
    }

    /// One scan's token. A 401 gets one fresh token per scan; another 401 means access is lost.
    private struct Session {
        var token: String
        var refreshed = false
    }

    public func scan() async -> GmailScanOutcome {
        guard let email = await settings.gmailAccountEmail() else { return GmailScanOutcome(result: .notConnected, newSuggestions: []) }
        let startedAt = now() / 1000
        let day = today()
        var session: Session
        switch await tokens.silentToken() {
        case .granted(let value): session = Session(token: value)
        case .needsUser:
            // A disconnect during silentToken has already wiped the flag: leave it off.
            guard await settings.gmailAccountEmail() == email else { return GmailScanOutcome(result: .notConnected, newSuggestions: []) }
            await settings.setGmailNeedsReconnect(true)
            return GmailScanOutcome(result: .needsReconnect, newSuggestions: [])
        case .notConfigured: return GmailScanOutcome(result: .notConnected, newSuggestions: [])
        case .offline: return GmailScanOutcome(result: .offline, newSuggestions: [])
        case .failed: return GmailScanOutcome(result: .partial, newSuggestions: [])
        }
        if await settings.gmailNeedsReconnect() { await settings.setGmailNeedsReconnect(false) }

        let query = GmailQuery.build(lastCheckEpochSeconds: await settings.gmailLastCheck())
        var found: [GmailSuggestion] = []
        var read: [String] = []
        var stop: Stop?
        var pageToken: String?
        listing: repeat {
            if Task.isCancelled { stop = .partial; break }
            let page: GmailListResponse
            do {
                page = try await call(&session) { try await self.api.list(token: $0, query: query, pageToken: pageToken) }
            } catch {
                stop = stopReason(error) ?? .partial
                break
            }
            let ids = page.messages.map(\.id)
            let seen = await store.seen(ids)
            for id in ids where !seen.contains(id) && !read.contains(id) {
                if read.count == Self.maxMessages || Task.isCancelled { stop = .partial; break listing }
                do {
                    found += try await readMessage(id, session: &session, today: day)
                } catch {
                    if let reason = stopReason(error) { stop = reason; break listing }
                    // Undecodable or gone: skip it, and mark it seen below.
                }
                read.append(id)
            }
            pageToken = page.nextPageToken
        } while pageToken != nil

        // Disconnect, sign-out or a user switch ran while this scan read mail: the wipe is done, so write nothing.
        // ponytail: one check before the writes; a wipe landing between it and the writes (no network) is not covered.
        guard await settings.gmailAccountEmail() == email else { return GmailScanOutcome(result: .notConnected, newSuggestions: []) }
        let existing = Set(await store.all().map(\.id))
        let new = GmailSuggestions.newOnly(found, existingIds: existing, tracked: await tracked.trackedKeys())
        if !new.isEmpty { await store.insert(new) }
        if !read.isEmpty { await store.markSeen(read) }
        if let stop {
            if stop == .accessLost { await settings.setGmailNeedsReconnect(true) }
            return GmailScanOutcome(result: stop.result, newSuggestions: new)
        }
        await settings.setGmailLastCheck(startedAt)
        return GmailScanOutcome(result: .completed, newSuggestions: new)
    }

    /// Deletes past suggestions and marks added the pending ones tracked another way.
    /// The caller removes the notifications of every returned id.
    public func cleanup() async -> GmailCleanup {
        let result = GmailSuggestions.cleanup(await store.all(), today: today(), tracked: await tracked.trackedKeys())
        if !result.deleteIds.isEmpty { await store.delete(result.deleteIds) }
        if !result.markAddedIds.isEmpty { await store.setStatus(result.markAddedIds, .added) }
        return result
    }

    private func readMessage(_ id: String, session: inout Session, today day: LocalDate) async throws -> [GmailSuggestion] {
        let message = try await call(&session) { try await self.api.message(token: $0, id: id) }
        let content = message.content()
        if GmailSuggestions.isCancellation(content.subject) { return [] }
        let sentMs = message.internalDate.flatMap { Int64($0) }
        // A date with no year is read against the email's own date, not today: "15 Jul" in a June email is
        // last July (dropped by fromTicket as past), not next July. The legs are still filtered by today.
        let reference = sentMs.map { min(LocalDate(year: 1970, month: 1, day: 1).adding(days: Int($0 / 86_400_000)), day) } ?? day
        var ticket = parseTicketText(content.text, today: reference)
        if ticket.flights.isEmpty, let attachmentId = content.pdfAttachmentId, content.pdfSize <= Self.maxPdfBytes {
            let data = try await call(&session) { try await self.api.attachment(token: $0, messageId: id, attachmentId: attachmentId) }
            ticket = parseTicketText(await pdf.read(data), today: reference)
        }
        // The email's own date, so `newOnly` keeps the newest email when several mention one flight.
        let foundAt = sentMs ?? now()
        return GmailSuggestions.fromTicket(ticket, messageId: id, subject: content.subject, today: day, foundAt: foundAt)
    }

    /// Runs `operation`; on the scan's first 401 gets a fresh token and retries. Any later 401 means access is lost.
    /// A refresh that fails throws a `Stop`, never a skip, so the email stays unseen and is read next scan.
    private func call<T>(_ session: inout Session, _ operation: (String) async throws -> T) async throws -> T {
        do {
            return try await operation(session.token)
        } catch GmailFailure.unauthorized where !session.refreshed {
            session.refreshed = true
            switch await tokens.refresh() {
            case .granted(let fresh): session.token = fresh
            case .offline: throw Stop.offline
            case .failed, .notConfigured: throw Stop.partial
            case .needsUser: throw Stop.accessLost
            }
            return try await operation(session.token)
        }
    }

    /// Nil means "skip this message": it is undecodable or gone, not a reason to stop the scan.
    private func stopReason(_ error: Error) -> Stop? {
        if Task.isCancelled { return .partial }
        if let stop = error as? Stop { return stop }
        switch error as? GmailFailure {
        case .unauthorized, .accessLost: return .accessLost
        case .rateLimited: return .partial
        case .offline: return .offline
        case .other, nil: return nil
        }
    }
}
