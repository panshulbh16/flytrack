import FlyTrackCore
import SwiftUI
import WatchConnectivity
import WidgetKit

// P3 B9: the Apple Watch app. It shows the next or current flight the phone sent over WatchConnectivity
// (`updateApplicationContext`, see ios/FlyTrack/WatchSync.swift), then the next few. No network of its own.

@main
struct FlyTrackWatchApp: App {
    @StateObject private var store = WatchFlightStore()

    var body: some Scene {
        WindowGroup {
            NextFlightScreen(store: store)
        }
    }
}

/// Receives the phone's application context, keeps it for the complication and asks WidgetKit to redraw.
@MainActor
final class WatchFlightStore: ObservableObject {
    @Published private(set) var payload: WatchPayload?
    private let receiver = WatchContextReceiver()

    init() {
        payload = WatchSnapshotStore.load()
        receiver.onContext = { [weak self] text in self?.apply(text) }
        receiver.start()
    }

    private func apply(_ text: String?) {
        guard let text, let next = WatchPayloadCodec.decode(text) else { return }
        // An older context delivered late must not replace a newer one.
        if let current = payload, current.at > next.at { return }
        let changed = WatchPayloadCodec.differs(payload, next)
        payload = next
        WatchSnapshotStore.save(text)
        if changed { WidgetCenter.shared.reloadTimelines(ofKind: WatchContract.complicationKind) }
    }
}

/// WCSession delegate. Delivers the context text on the main actor.
final class WatchContextReceiver: NSObject, WCSessionDelegate, @unchecked Sendable {
    /// Set once before `start()`, read on the main actor only.
    @MainActor var onContext: ((String?) -> Void)?

    @MainActor func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        // The last context the phone sent while this app was not running.
        deliver(session.receivedApplicationContext[WatchContract.contextKey] as? String)
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        deliver(applicationContext[WatchContract.contextKey] as? String)
    }

    private func deliver(_ text: String?) {
        Task { @MainActor in self.onContext?(text) }
    }
}

struct NextFlightScreen: View {
    @ObservedObject var store: WatchFlightStore

    var body: some View {
        TimelineView(.everyMinute) { context in
            content(at: context.date)
        }
    }

    @ViewBuilder
    private func content(at now: Date) -> some View {
        let snapshot = store.payload?.widgetSnapshot ?? .empty
        if let flight = widgetFlight(in: snapshot, at: now) {
            let display = widgetDisplay(flight, at: now)
            let later = snapshot.flights.filter { $0.id != flight.id && (widgetVisibleUntil($0).map { $0 > now } ?? true) }
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    FlightCardView(display: display)
                    if !later.isEmpty {
                        Text(String(localized: "Later"))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(WatchColor.muted)
                            .padding(.top, 4)
                        ForEach(later, id: \.id) { next in
                            LaterRow(display: widgetDisplay(next, at: now))
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "Next flight"))
        } else {
            VStack(spacing: 8) {
                Image(systemName: "airplane").font(.title2).foregroundStyle(WatchColor.accent).accessibilityHidden(true)
                Text(String(localized: "No upcoming flights")).font(.headline).foregroundStyle(WatchColor.ink)
                Text(String(localized: "Track one in HeyGate on your iPhone."))
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(WatchColor.muted)
            }
            .padding()
        }
    }
}

private struct FlightCardView: View {
    let display: WidgetDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: display.flight.designator).font(.headline).foregroundStyle(WatchColor.ink)
                Spacer()
                Image(systemName: WatchWording.icon(display.phase)).foregroundStyle(WatchWording.color(display)).accessibilityHidden(true)
            }
            Text(verbatim: "\(display.flight.dep) → \(display.flight.arr)")
                .font(.system(.title3, design: .rounded).monospacedDigit())
                .foregroundStyle(WatchColor.ink)
            Text(WatchWording.status(display)).font(.footnote.weight(.semibold)).foregroundStyle(WatchWording.color(display))
            if let time = WatchWording.headlineTime(display) {
                HStack(spacing: 4) {
                    Text(verbatim: time).font(.system(.title2, design: .rounded).monospacedDigit()).foregroundStyle(WatchColor.ink)
                    if let scheduled = display.schedDepTime {
                        Text(verbatim: scheduled).font(.footnote).strikethrough().foregroundStyle(WatchColor.muted)
                    }
                }
            }
            if let gate = WatchWording.gate(display) {
                Text(gate).font(.footnote).foregroundStyle(WatchColor.ink)
            }
            if let target = display.countdownTarget {
                Text(target, style: .relative).font(.footnote).foregroundStyle(WatchColor.muted)
            }
            if let progress = display.progress {
                ProgressView(value: progress).tint(WatchColor.accent)
                    .accessibilityLabel(String(localized: "Flight progress"))
            }
        }
        .padding(10)
        .background(WatchColor.card, in: .rect(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

private struct LaterRow: View {
    let display: WidgetDisplay

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: display.flight.designator).font(.footnote.weight(.semibold)).foregroundStyle(WatchColor.ink)
                Text(verbatim: "\(display.flight.dep) → \(display.flight.arr)").font(.caption2).foregroundStyle(WatchColor.muted)
            }
            Spacer()
            if let time = display.depTime {
                Text(verbatim: time).font(.footnote.monospacedDigit()).foregroundStyle(WatchColor.ink)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
