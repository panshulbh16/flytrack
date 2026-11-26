import FlyTrackCore
import Foundation
import WatchConnectivity

/// P3 B9: sends the next flights to the Apple Watch app (`FlyTrackWatch`) with
/// `WCSession.updateApplicationContext`, whose latest value the watch receives even when it was not running. Called
/// with the widget snapshot on every sync (`AppShell.publishWidgets`); identical payloads are not resent.
@MainActor
final class WatchSender: NSObject {
    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }
    fileprivate var lastSent: WatchPayload?
    private var pending: WatchPayload?
    private var activating = false

    func publish(flights: [Flight], now: Date) {
        send(watchPayload(flights: flights, now: now))
    }

    /// Sign-out: the watch shows its empty state.
    func clear() {
        lastSent = nil
        send(WatchPayload(at: Date(), flights: []), force: true)
    }

    private func send(_ payload: WatchPayload, force: Bool = false) {
        guard let session else { return }
        guard force || WatchPayloadCodec.differs(lastSent, payload) else { return }
        guard session.activationState == .activated else {
            pending = payload
            activate(session)
            return
        }
        // No paired watch or the app is not installed there: nothing to do (and nothing to retry).
        guard session.isPaired, session.isWatchAppInstalled, let text = WatchPayloadCodec.encode(payload) else { return }
        do {
            try session.updateApplicationContext([WatchContract.contextKey: text])
            lastSent = payload
        } catch {
            // The context is resent with the next sync.
        }
    }

    private func activate(_ session: WCSession) {
        guard !activating else { return }
        activating = true
        session.delegate = self
        session.activate()
    }

    fileprivate func activated() {
        activating = false
        if let pending {
            self.pending = nil
            send(pending, force: true)
        }
    }
}

extension WatchSender: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.activated() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // A different watch was paired: activate again for it; the next sync sends it the flights.
        Task { @MainActor in
            self.lastSent = nil
            WCSession.default.activate()
        }
    }
}
