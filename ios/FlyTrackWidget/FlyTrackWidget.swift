import FlyTrackCore
import SwiftUI
import WidgetKit

// Home Screen and Lock Screen widgets (docs/P3_CONTRACTS.md A4): the next or current flight, read from the snapshot
// the app writes into the App Group `group.app.flytrack` on every sync. No network here. Timeline entries are made at
// the flight's milestone times (boarding, departure, arrival, roll-over to the next flight); the app also reloads
// the widget whenever the snapshot changes.

@main
struct FlyTrackWidgetBundle: WidgetBundle {
    var body: some Widget { FlyTrackWidget() }
}

struct FlyTrackWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetContract.kind, provider: FlightProvider()) { entry in
            FlightWidgetView(entry: entry)
        }
        .configurationDisplayName(String(localized: "Next flight"))
        .description(String(localized: "Your next or current flight: status, times and gate."))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

// MARK: - timeline

struct FlightEntry: TimelineEntry {
    let date: Date
    /// Nil: no upcoming flight.
    let display: WidgetDisplay?
}

struct FlightProvider: TimelineProvider {
    func placeholder(in context: Context) -> FlightEntry {
        FlightEntry(date: Date(), display: widgetDisplay(sampleFlight(now: Date()), at: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (FlightEntry) -> Void) {
        let now = Date()
        if context.isPreview {
            completion(placeholder(in: context))
            return
        }
        completion(entry(at: now, snapshot: loadSnapshot()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<FlightEntry>) -> Void) {
        let now = Date()
        let snapshot = loadSnapshot()
        let dates = widgetEntryDates(snapshot, now: now)
        let entries = dates.map { entry(at: $0, snapshot: snapshot) }
        completion(Timeline(entries: entries, policy: .after(widgetReloadDate(entries: dates, now: now))))
    }

    private func entry(at date: Date, snapshot: WidgetSnapshot) -> FlightEntry {
        FlightEntry(date: date, display: widgetFlight(in: snapshot, at: date).map { widgetDisplay($0, at: date) })
    }

    private func loadSnapshot() -> WidgetSnapshot {
        let data = UserDefaults(suiteName: WidgetContract.appGroup)?.data(forKey: WidgetContract.snapshotKey)
        return WidgetSnapshotCodec.decode(data) ?? .empty
    }

    /// Gallery preview only; never shown as a real flight.
    private func sampleFlight(now: Date) -> WidgetFlight {
        WidgetFlight(
            id: "00000000-0000-0000-0000-000000000000", designator: "6E 2134", dep: "DEL", arr: "BOM",
            depCity: "Delhi", arrCity: "Mumbai", depTz: "Asia/Kolkata", arrTz: "Asia/Kolkata", status: "scheduled",
            schedDep: now.addingTimeInterval(2 * 3600), schedArr: now.addingTimeInterval(4 * 3600 + 10 * 60),
            depTerminal: "1", depGate: "42B"
        )
    }
}

// MARK: - views

struct FlightWidgetView: View {
    let entry: FlightEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let display = entry.display {
                switch family {
                case .systemMedium: MediumView(display: display)
                case .accessoryRectangular: RectangularView(display: display)
                case .accessoryInline: InlineView(display: display)
                default: SmallView(display: display)
                }
            } else {
                EmptyFlightView(family: family)
            }
        }
        .widgetURL(entry.display.flatMap { URL(string: "heygate://flight/\($0.flight.id)") })
        .containerBackground(for: .widget) {
            if isAccessory { Color.clear } else { FlyColor.paper }
        }
    }

    private var isAccessory: Bool {
        family == .accessoryRectangular || family == .accessoryInline || family == .accessoryCircular
    }
}

private struct SmallView: View {
    let display: WidgetDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: display.flight.designator).font(FlyFont.sans(13, weight: .medium, relativeTo: .caption))
                Spacer(minLength: 4)
                Image(systemName: phaseIcon(display.phase)).font(.caption)
            }
            Text(verbatim: "\(display.flight.dep) → \(display.flight.arr)")
                .font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
            Text(statusText(display))
                .font(FlyFont.serifItalic(20, relativeTo: .title3))
                .foregroundStyle(statusColor(display))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
            if let time = headlineTime(display) {
                Text(verbatim: time).font(FlyFont.numeral(26))
            }
            if let line = gateLine(display) {
                Text(line).font(FlyFont.sans(12, relativeTo: .caption)).foregroundStyle(FlyColor.muted).lineLimit(1)
            } else if let target = display.countdownTarget {
                Text(target, style: .relative).font(FlyFont.sans(12, relativeTo: .caption)).foregroundStyle(FlyColor.muted)
            }
        }
        .foregroundStyle(FlyColor.ink)
    }
}

private struct MediumView: View {
    let display: WidgetDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: display.flight.designator).font(FlyFont.sans(14, weight: .medium, relativeTo: .subheadline))
                Spacer(minLength: 8)
                Text(statusText(display))
                    .font(FlyFont.serifItalic(20, relativeTo: .title3))
                    .foregroundStyle(statusColor(display))
                    .lineLimit(1)
            }
            HStack(alignment: .top) {
                endpoint(code: display.flight.dep, city: display.flight.depCity, time: display.depTime, struck: display.schedDepTime, alignment: .leading)
                Spacer(minLength: 8)
                Image(systemName: "airplane").foregroundStyle(FlyColor.accentLine).padding(.top, 6)
                Spacer(minLength: 8)
                endpoint(code: display.flight.arr, city: display.flight.arrCity, time: display.arrTime, struck: nil, alignment: .trailing)
            }
            if let progress = display.progress {
                ProgressView(value: progress).tint(FlyColor.accentLine)
            }
            Spacer(minLength: 0)
            HStack {
                if let line = gateLine(display) {
                    Text(line).font(FlyFont.sans(13, relativeTo: .footnote))
                }
                Spacer(minLength: 8)
                if let target = display.countdownTarget {
                    Text(target, style: .relative)
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(FlyColor.muted)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
        .foregroundStyle(FlyColor.ink)
    }

    private func endpoint(code: String, city: String?, time: String?, struck: String?, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 0) {
            Text(verbatim: code).font(FlyFont.sans(20, weight: .medium, relativeTo: .title3))
            if let city { Text(city).font(FlyFont.sans(12, relativeTo: .caption)).foregroundStyle(FlyColor.muted).lineLimit(1) }
            if let time {
                HStack(spacing: 4) {
                    if let struck {
                        Text(verbatim: struck).strikethrough().font(FlyFont.sans(12, relativeTo: .caption)).foregroundStyle(FlyColor.muted)
                    }
                    Text(verbatim: time).font(FlyFont.numeral(20))
                }
            }
        }
    }
}

/// Lock Screen, rectangular: three short lines in the system's vibrant style (no brand colours).
private struct RectangularView: View {
    let display: WidgetDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: "\(display.flight.designator)  \(display.flight.dep)→\(display.flight.arr)")
                .font(.headline)
                .widgetAccentable()
            HStack(spacing: 4) {
                Text(statusText(display))
                if let time = headlineTime(display) { Text(verbatim: "· \(time)") }
            }
            .font(.body)
            if let line = gateLine(display) {
                Text(line).font(.caption)
            } else if let target = display.countdownTarget {
                Text(target, style: .relative).font(.caption)
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Lock Screen, one line above the clock.
private struct InlineView: View {
    let display: WidgetDisplay

    var body: some View {
        var parts = [display.flight.designator, statusText(display)]
        if let gate = display.gate { parts.append(String(localized: "Gate \(gate)")) }
        else if let belt = display.belt { parts.append(String(localized: "Belt \(belt)")) }
        else if let time = headlineTime(display) { parts.append(time) }
        return Label(parts.joined(separator: " · "), systemImage: "airplane")
    }
}

private struct EmptyFlightView: View {
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryInline:
            Label(String(localized: "No upcoming flights"), systemImage: "airplane")
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(String(localized: "No upcoming flights")).font(.headline)
                Text(String(localized: "Track one in HeyGate")).font(.caption)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "airplane").foregroundStyle(FlyColor.accentLine)
                Text(String(localized: "No upcoming flights")).font(FlyFont.serifItalic(20, relativeTo: .title3))
                Text(String(localized: "Track one in HeyGate")).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
            .foregroundStyle(FlyColor.ink)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

// MARK: - wording

private func statusText(_ display: WidgetDisplay) -> String {
    switch display.phase {
    case .cancelled: return String(localized: "Cancelled")
    case .diverted: return String(localized: "Diverted")
    case .landed: return String(localized: "Landed")
    case .inAir: return String(localized: "In the air")
    case .departed: return String(localized: "Departed")
    case .boarding:
        return display.flight.flightStatus == .gateClosed ? String(localized: "Gate closed") : String(localized: "Boarding")
    case .checkIn, .upcoming:
        if let late = display.lateMin { return String(localized: "\(late) min late") }
        return display.phase == .checkIn ? String(localized: "Check-in open") : String(localized: "On time")
    }
}

private func statusColor(_ display: WidgetDisplay) -> Color {
    switch display.phase {
    case .cancelled, .diverted: return FlyColor.cancelled
    case .boarding, .departed, .inAir: return FlyColor.accent
    case .landed, .checkIn, .upcoming: return display.lateMin != nil ? FlyColor.delayed : FlyColor.onTime
    }
}

/// Departure time before take-off, arrival time after.
private func headlineTime(_ display: WidgetDisplay) -> String? {
    switch display.phase {
    case .upcoming, .checkIn, .boarding, .cancelled: return display.depTime
    case .departed, .inAir, .landed, .diverted: return display.arrTime
    }
}

private func gateLine(_ display: WidgetDisplay) -> String? {
    if let belt = display.belt { return String(localized: "Belt \(belt)") }
    switch (display.gate, display.terminal) {
    case let (gate?, terminal?): return String(localized: "Gate \(gate) · T\(terminal)")
    case let (gate?, nil): return String(localized: "Gate \(gate)")
    case let (nil, terminal?): return String(localized: "Terminal \(terminal)")
    case (nil, nil): return nil
    }
}

private func phaseIcon(_ phase: WidgetPhase) -> String {
    switch phase {
    case .upcoming, .checkIn: return "clock"
    case .boarding: return "figure.walk"
    case .departed, .inAir: return "airplane"
    case .landed: return "airplane.arrival"
    case .cancelled: return "xmark.circle"
    case .diverted: return "arrow.triangle.turn.up.right.diamond"
    }
}
