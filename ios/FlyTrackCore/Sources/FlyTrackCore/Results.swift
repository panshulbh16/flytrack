import Foundation

public enum TrackField: String, Sendable, Equatable {
    case pnr, seat, note
}

public enum ActionResult: Sendable, Equatable {
    case success
    case offline
    case notConfigured
    case notFound
    case trackingLimitReached
    case invalidInput(TrackField)
    case failed(String?)

    public var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }
}

public enum BadInputReason: String, Sendable, Equatable {
    case flightNumber, date, other
}

public enum SearchResult: Sendable, Equatable {
    case found([Flight])
    case notFound
    case rateLimited
    case badInput(BadInputReason)
    case providerError
    case offline
    case notConfigured
}

public enum TrackResult: Sendable, Equatable {
    case tracked(TrackedFlight)
    case alreadyTracked(TrackedFlight)
    case trackingLimitReached(String?)
    case invalidInput(TrackField)
    case flightNotFound
    case offline
    case notConfigured
    case failed(String?)
}

public enum AuthResult: Sendable, Equatable {
    case success
    case offline
    case notConfigured
    case googleAccountInUse
    case failed(String?)

    public var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }
}

public enum SessionState: Sendable, Equatable {
    case loading
    case notConfigured
    case signedOut
    case signedIn(userId: String, isAnonymous: Bool, email: String?)
}
