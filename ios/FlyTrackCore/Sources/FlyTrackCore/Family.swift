import Foundation

// P3 B13 family sharing (Pro) on iOS: `my_family()` and the typed errors of the family RPCs
// (supabase/migrations/20261126000029_family.sql), and the screen model. Same rules as Android's feature/family.

public enum FamilyRole: String, Sendable, Equatable { case owner, member }

public struct FamilyMember: Hashable, Sendable, Identifiable {
    public var userId: String
    public var displayName: String?
    public var joinedAt: Date?
    public var id: String { userId }

    public init(userId: String, displayName: String?, joinedAt: Date?) {
        self.userId = userId
        self.displayName = displayName
        self.joinedAt = joinedAt
    }
}

/// The owner's open invite (8 characters, like friend codes).
public struct FamilyInvite: Hashable, Sendable {
    public var code: String
    public var expiresAt: Date?

    public init(code: String, expiresAt: Date?) {
        self.code = code
        self.expiresAt = expiresAt
    }
}

public let defaultFamilyMaxMembers = 5

/// `my_family()`: the family the caller owns or belongs to.
public struct FamilyInfo: Hashable, Sendable {
    public var familyId: String
    public var role: FamilyRole
    public var ownerId: String
    public var ownerName: String?
    /// The owner has Pro of their own (the family's Pro is active).
    public var ownerPro: Bool
    /// The caller is Pro only through this family: show "Pro via {owner}'s family".
    public var proViaFamily: Bool
    /// Everyone except the owner, oldest first.
    public var members: [FamilyMember]
    public var maxMembers: Int
    /// The owner can hand out an invite now (yearly or lifetime Pro, a free seat).
    public var canInvite: Bool
    /// Owner only.
    public var invite: FamilyInvite?

    public init(
        familyId: String, role: FamilyRole, ownerId: String, ownerName: String?, ownerPro: Bool, proViaFamily: Bool,
        members: [FamilyMember], maxMembers: Int = defaultFamilyMaxMembers, canInvite: Bool, invite: FamilyInvite?
    ) {
        self.familyId = familyId
        self.role = role
        self.ownerId = ownerId
        self.ownerName = ownerName
        self.ownerPro = ownerPro
        self.proViaFamily = proViaFamily
        self.members = members
        self.maxMembers = maxMembers
        self.canInvite = canInvite
        self.invite = invite
    }

    public var isOwner: Bool { role == .owner }
    public var seatsLeft: Int { max(0, maxMembers - members.count) }
}

/// Why a family RPC refused (the raised message, as in the migration's header).
public enum FamilyError: String, Sendable, Equatable {
    /// `family_requires_yearly` (42501): only a yearly or lifetime Pro owner can create a family.
    case requiresYearly
    /// `already_in_family` (23505).
    case alreadyInFamily
    /// `invalid_code` (P0002): unknown, used or expired.
    case invalidCode
    /// `own_code` (22023).
    case ownCode
    /// `family_full` (P0001): five members besides the owner.
    case full
    /// `family_inactive` (P0001): the owner's Pro has ended.
    case inactive
    /// `not_member` (P0002).
    case notMember
    /// `not_owner` (42501).
    case notOwner
}

/// Maps a refusal: the raised message decides first, the SQLSTATE only where it is unique.
public func familyError(code: String?, message: String?) -> FamilyError? {
    let text = message ?? ""
    if text.contains("family_requires_yearly") { return .requiresYearly }
    if text.contains("already_in_family") { return .alreadyInFamily }
    if text.contains("invalid_code") { return .invalidCode }
    if text.contains("own_code") { return .ownCode }
    if text.contains("family_full") { return .full }
    if text.contains("family_inactive") { return .inactive }
    if text.contains("not_member") { return .notMember }
    if text.contains("not_owner") { return .notOwner }
    if code == "23505" { return .alreadyInFamily }
    if code == "22023" { return .ownCode }
    return nil
}

/// `accept_family_invite` succeeded.
public struct JoinedFamily: Hashable, Sendable {
    public var familyId: String
    public var ownerId: String
    public var ownerName: String?

    public init(familyId: String, ownerId: String, ownerName: String?) {
        self.familyId = familyId
        self.ownerId = ownerId
        self.ownerName = ownerName
    }
}

private func familyText(_ value: Any?) -> String? {
    JSON.string(value)?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
}

/// What `my_family()` answered: SQL null (no family) or a family. Nil for an unreadable body.
public enum FamilyAnswer: Hashable, Sendable {
    case noFamily
    case family(FamilyInfo)
}

public func parseFamily(_ data: Data) -> FamilyAnswer? {
    if rpcIsNull(data) { return .noFamily }
    guard let o = JSON.object(from: data) else { return nil }
    return parseFamily(o).map(FamilyAnswer.family)
}

/// Nil when the object misses `family_id` or the owner.
public func parseFamily(_ o: [String: Any]) -> FamilyInfo? {
    guard let familyId = familyText(o["family_id"]), let owner = JSON.object(o["owner"]),
          let ownerId = familyText(owner["user_id"]) else { return nil }
    let members = (o["members"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.compactMap { m -> FamilyMember? in
        guard let id = familyText(m["user_id"]) else { return nil }
        return FamilyMember(userId: id, displayName: familyText(m["display_name"]), joinedAt: parseInstant(JSON.string(m["joined_at"])))
    }
    let invite = JSON.object(o["invite"]).flatMap { i -> FamilyInvite? in
        familyText(i["code"]).map { FamilyInvite(code: $0, expiresAt: parseInstant(JSON.string(i["expires_at"]))) }
    }
    return FamilyInfo(
        familyId: familyId,
        role: JSON.string(o["role"]) == "owner" ? .owner : .member,
        ownerId: ownerId,
        ownerName: familyText(owner["display_name"]),
        ownerPro: JSON.bool(o["owner_pro"]) ?? false,
        proViaFamily: JSON.bool(o["pro_via_family"]) ?? false,
        members: members,
        maxMembers: JSON.int(o["max_members"]) ?? defaultFamilyMaxMembers,
        canInvite: JSON.bool(o["can_invite"]) ?? false,
        invite: invite
    )
}

/// `accept_family_invite`.
public func parseJoinedFamily(_ data: Data) -> JoinedFamily? {
    guard let o = JSON.object(from: data), let familyId = familyText(o["family_id"]), let ownerId = familyText(o["owner_id"]) else {
        return nil
    }
    return JoinedFamily(familyId: familyId, ownerId: ownerId, ownerName: familyText(o["owner_name"]))
}

/// The link that opens the join screen with `code` filled in.
public func familyInviteLink(code: String) -> String { "heygate://family?code=\(code)" }

/// The code of a `heygate://family?code=…` link (or a bare code), normalised like friend codes.
public func familyInviteCode(fromURL url: URL) -> String? {
    guard url.scheme?.lowercased() == "heygate", url.host?.lowercased() == "family" else { return nil }
    let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value
    return normalizeInviteCode(query ?? "")
}

@MainActor
public protocol FamilyRepository: AnyObject {
    func myFamily() async -> RemoteResult<FamilyAnswer>
    /// `create_family_invite()`: the owner's open code, or a new one (creates the family on first use).
    func createInvite() async -> RemoteResult<String>
    func acceptInvite(code: String) async -> RemoteResult<JoinedFamily>
    func leave() async -> RemoteResult<Bool>
    func removeMember(_ userId: String) async -> RemoteResult<Bool>
}

public enum FamilyProblem: Equatable, Sendable {
    case offline, notConfigured, failed
    case refused(FamilyError)
}

public enum FamilyPhase: Equatable, Sendable {
    case loading
    /// In no family: create one (yearly / lifetime Pro) or join with a code.
    case noFamily
    case family(FamilyInfo)
}

public struct FamilyState: Equatable, Sendable {
    public var phase: FamilyPhase
    public var busy: Bool
    public var problem: FamilyProblem?
    public var codeInput: String
    /// A join just succeeded: "You joined {owner}'s family".
    public var joined: JoinedFamily?
    /// An invite code was just created and should be shared.
    public var newInvite: String?

    public init(phase: FamilyPhase = .loading, busy: Bool = false, problem: FamilyProblem? = nil, codeInput: String = "", joined: JoinedFamily? = nil, newInvite: String? = nil) {
        self.phase = phase
        self.busy = busy
        self.problem = problem
        self.codeInput = codeInput
        self.joined = joined
        self.newInvite = newInvite
    }

    public var family: FamilyInfo? { if case .family(let f) = phase { return f }; return nil }
    /// The typed code once it can be one (8 characters of the invite alphabet).
    public var normalizedCode: String? { normalizeInviteCode(codeInput) }
}

/// The family screen: show the family, invite (owner), remove a member (owner), leave, or join with a code.
@MainActor
public final class FamilyModel {
    public private(set) var state: FamilyState
    public var onChange: ((FamilyState) -> Void)?
    private let repository: FamilyRepository
    /// After a join or leave the Pro state changed: the app reloads entitlements.
    private let onEntitlementChanged: @MainActor () async -> Void

    public init(repository: FamilyRepository, prefillCode: String? = nil, onEntitlementChanged: @escaping @MainActor () async -> Void = {}) {
        self.repository = repository
        self.onEntitlementChanged = onEntitlementChanged
        state = FamilyState(codeInput: prefillCode.flatMap(normalizeInviteCode) ?? "")
    }

    public func load() async {
        let result = await repository.myFamily()
        switch result {
        case .ok(.noFamily): state.phase = .noFamily; state.problem = nil
        case .ok(.family(let info)): state.phase = .family(info); state.problem = nil
        default:
            if case .loading = state.phase { state.phase = .noFamily }
            state.problem = problem(result)
        }
        publish()
    }

    public func setCode(_ text: String) {
        state.codeInput = String(text.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(8))
        if case .refused(.invalidCode) = state.problem { state.problem = nil }
        if case .refused(.ownCode) = state.problem { state.problem = nil }
        publish()
    }

    /// Owner (or a yearly / lifetime Pro user without a family): get the invite code to share.
    public func createInvite() async {
        guard begin() else { return }
        let result = await repository.createInvite()
        if case .ok(let code) = result {
            state.newInvite = code
            await reloadAfterChange(entitlement: false)
        } else {
            finish(problem(result))
        }
    }

    public func inviteShared() {
        state.newInvite = nil
        publish()
    }

    public func join() async {
        guard let code = state.normalizedCode else {
            state.problem = .refused(.invalidCode)
            publish()
            return
        }
        guard begin() else { return }
        let result = await repository.acceptInvite(code: code)
        if case .ok(let joined) = result {
            state.joined = joined
            state.codeInput = ""
            await reloadAfterChange(entitlement: true)
        } else {
            finish(problem(result))
        }
    }

    public func leave() async {
        guard begin() else { return }
        let result = await repository.leave()
        if result.isOk {
            state.joined = nil
            await reloadAfterChange(entitlement: true)
        } else {
            finish(problem(result))
        }
    }

    public func remove(_ userId: String) async {
        guard begin() else { return }
        let result = await repository.removeMember(userId)
        if result.isOk {
            await reloadAfterChange(entitlement: false)
        } else {
            finish(problem(result))
        }
    }

    public func clearProblem() {
        state.problem = nil
        publish()
    }

    private func begin() -> Bool {
        if state.busy { return false }
        state.busy = true
        state.problem = nil
        publish()
        return true
    }

    private func reloadAfterChange(entitlement: Bool) async {
        if entitlement { await onEntitlementChanged() }
        let result = await repository.myFamily()
        switch result {
        case .ok(.noFamily): state.phase = .noFamily
        case .ok(.family(let info)): state.phase = .family(info)
        default: break
        }
        finish(nil)
    }

    private func finish(_ problem: FamilyProblem?) {
        state.busy = false
        state.problem = problem
        publish()
    }

    private func problem<T: Sendable>(_ result: RemoteResult<T>) -> FamilyProblem? {
        switch result {
        case .ok: return nil
        case .offline: return .offline
        case .notConfigured: return .notConfigured
        case .failed: return .failed
        case .refused(let code, let message): return familyError(code: code, message: message).map(FamilyProblem.refused) ?? .failed
        }
    }

    private func publish() { onChange?(state) }
}
