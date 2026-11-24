import ActivityKit
import SwiftUI
import WidgetKit

// ponytail: system fonts and semantic colours only. The app's fonts are not bundled in this extension, and the
// palette is being reworked; move to the app's tokens once Theme.swift is shared here.

@main
struct FlightActivityBundle: WidgetBundle {
    var body: some Widget { FlightLiveActivity() }
}

struct FlightLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlightActivityAttributes.self) { context in
            LockScreenView(flight: context.attributes, state: context.state)
                .padding(16)
                .widgetURL(deepLink(context.attributes))
        } dynamicIsland: { context in
            let flight = context.attributes
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Endpoint(code: flight.dep, time: state.estDep ?? state.schedDep, tz: flight.depTz, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Endpoint(code: flight.arr, time: state.estArr ?? state.schedArr, tz: flight.arrTz, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.center) {
                    HStack(spacing: 4) {
                        Text("\(flight.carrier) \(flight.number)")
                        Text("·")
                        StatusText(state: state)
                    }
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Details(state: state)
                        Spacer()
                        CountdownLabel(state: state)
                    }
                    .font(.callout)
                }
            } compactLeading: {
                let next = NextThing(flight: flight, state: state)
                Label(next.text, systemImage: next.icon)
                    .font(.caption.weight(.semibold))
                    .accessibilityLabel(next.spoken)
            } compactTrailing: {
                if let target = countdownTarget(state) {
                    Countdown(to: target.date)
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: 56)
                } else {
                    StatusText(state: state).font(.caption.weight(.semibold))
                }
            } minimal: {
                Image(systemName: NextThing(flight: flight, state: state).icon)
            }
            .widgetURL(deepLink(flight))
        }
    }
}

private func deepLink(_ flight: FlightActivityAttributes) -> URL? {
    URL(string: "heygate://flight/\(flight.flightId)")
}

private struct LockScreenView: View {
    let flight: FlightActivityAttributes
    let state: FlightActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(flight.carrier) \(flight.number)").font(.subheadline.weight(.semibold))
                Spacer()
                StatusText(state: state).font(.subheadline.weight(.semibold))
            }
            HStack(alignment: .firstTextBaseline) {
                Endpoint(code: flight.dep, time: state.estDep ?? state.schedDep, tz: flight.depTz, alignment: .leading)
                Spacer()
                Image(systemName: "airplane").foregroundStyle(.secondary).accessibilityHidden(true)
                Spacer()
                Endpoint(code: flight.arr, time: state.estArr ?? state.schedArr, tz: flight.arrTz, alignment: .trailing)
            }
            HStack {
                Details(state: state)
                Spacer()
                CountdownLabel(state: state)
            }
            .font(.footnote)
            Text("Updated \(state.updatedAt, style: .time)")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }
}

private struct Endpoint: View {
    let code: String
    let time: Date?
    let tz: String?
    let alignment: HorizontalAlignment

    var body: some View {
        VStack(alignment: alignment, spacing: 2) {
            Text(code).font(.title2.weight(.semibold)).monospaced()
            if let time {
                // Airport-local, like the departures board, whatever zone the phone is in.
                Text(time, style: .time)
                    .font(.subheadline)
                    .monospacedDigit()
                    .environment(\.timeZone, tz.flatMap(TimeZone.init(identifier:)) ?? .current)
            }
        }
    }
}

/// Gate before departure, belt after landing.
private struct Details: View {
    let state: FlightActivityAttributes.ContentState

    var body: some View {
        if state.isLanded || state.isAirborne {
            if let belt = state.belt { Text("Belt \(belt)") }
        } else {
            HStack(spacing: 6) {
                if let terminal = state.depTerminal { Text("T\(terminal)") }
                Text(state.depGate.map { String(localized: "Gate \($0)") } ?? String(localized: "Gate not announced"))
            }
        }
    }
}

private struct StatusText: View {
    let state: FlightActivityAttributes.ContentState

    var body: some View {
        switch state.status {
        case "cancelled": Text("Cancelled")
        case "diverted": Text("Diverted")
        case "landed", "arrived": Text("Landed")
        case "departed", "en_route", "approaching": Text("In the air")
        case "boarding": Text("Boarding")
        case "gate_closed": Text("Gate closed")
        default:
            let late = delayMinutes(state)
            if late >= 5 {
                Text("\(late) min late")
            } else {
                Text(state.estDep == nil ? "Scheduled" : "On time")
            }
        }
    }
}

private func delayMinutes(_ state: FlightActivityAttributes.ContentState) -> Int {
    guard let est = state.estDep, let sched = state.schedDep else { return 0 }
    return Int(est.timeIntervalSince(sched) / 60)
}

/// Departure until wheels-up, then arrival. Nothing once landed or cancelled.
private func countdownTarget(_ state: FlightActivityAttributes.ContentState) -> (label: LocalizedStringKey, date: Date)? {
    if state.isLanded || state.status == "cancelled" || state.status == "diverted" { return nil }
    if state.isAirborne { return (state.estArr ?? state.schedArr).map { ("Lands in", $0) } }
    return (state.estDep ?? state.schedDep).map { ("Departs in", $0) }
}

private struct CountdownLabel: View {
    let state: FlightActivityAttributes.ContentState

    var body: some View {
        if let target = countdownTarget(state) {
            HStack(spacing: 4) {
                Text(target.label).foregroundStyle(.secondary)
                Countdown(to: target.date)
            }
        }
    }
}

/// Ticks on its own between pushes.
private struct Countdown: View {
    let to: Date

    var body: some View {
        if to > .now {
            Text(timerInterval: Date.now...to, countsDown: true)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        } else {
            Text("Now")
        }
    }
}

/// The one thing to look for next, for the compact and minimal Dynamic Island.
private struct NextThing {
    let text: String
    let icon: String
    let spoken: String

    init(flight: FlightActivityAttributes, state: FlightActivityAttributes.ContentState) {
        if state.isLanded {
            text = state.belt ?? flight.arr
            icon = "suitcase"
            spoken = state.belt.map { String(localized: "Belt \($0)") } ?? String(localized: "Landed at \(flight.arr)")
        } else if state.isAirborne {
            text = flight.arr
            icon = "airplane"
            spoken = String(localized: "Flying to \(flight.arr)")
        } else {
            text = state.depGate ?? flight.dep
            icon = "airplane.departure"
            spoken = state.depGate.map { String(localized: "Gate \($0)") } ?? String(localized: "Departs \(flight.dep)")
        }
    }
}
