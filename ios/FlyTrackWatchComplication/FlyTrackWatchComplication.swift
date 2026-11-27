import FlyTrackCore
import SwiftUI
import WidgetKit

// P3 B9: the Apple Watch complication (WidgetKit accessory families). It reads the payload the watch app keeps in its
// App Group and redraws at the flight's milestones (boarding, departure, arrival), like the phone widget.

struct WatchFlightEntry: TimelineEntry {
    let date: Date
    let display: WidgetDisplay?
}

struct WatchFlightProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchFlightEntry {
        WatchFlightEntry(date: Date(), display: widgetDisplay(Self.sample, at: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchFlightEntry) -> Void) {
        let now = Date()
        if context.isPreview {
            completion(WatchFlightEntry(date: now, display: widgetDisplay(Self.sample, at: now)))
            return
        }
        completion(entry(WatchSnapshotStore.load()?.widgetSnapshot ?? .empty, at: now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchFlightEntry>) -> Void) {
        let now = Date()
        let snapshot = WatchSnapshotStore.load()?.widgetSnapshot ?? .empty
        let dates = widgetEntryDates(snapshot, now: now)
        completion(Timeline(entries: dates.map { entry(snapshot, at: $0) }, policy: .after(widgetReloadDate(entries: dates, now: now))))
    }

    private func entry(_ snapshot: WidgetSnapshot, at date: Date) -> WatchFlightEntry {
        WatchFlightEntry(date: date, display: widgetFlight(in: snapshot, at: date).map { widgetDisplay($0, at: date) })
    }

    static let sample = WidgetFlight(
        id: "sample", designator: "6E 2341", dep: "DEL", arr: "BOM", depTz: "Asia/Kolkata", arrTz: "Asia/Kolkata",
        status: FlightStatus.scheduled.wire, schedDep: Date().addingTimeInterval(2 * 3600),
        schedArr: Date().addingTimeInterval(4 * 3600 + 15 * 60), depTerminal: "1", depGate: "42B"
    )
}

@main
struct FlyTrackWatchComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WatchContract.complicationKind, provider: WatchFlightProvider()) { entry in
            WatchComplicationView(entry: entry)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName(String(localized: "Next flight"))
        .description(String(localized: "Your next or current flight: status, times and gate."))
        .supportedFamilies([.accessoryRectangular, .accessoryInline, .accessoryCircular, .accessoryCorner])
    }
}

struct WatchComplicationView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchFlightEntry

    var body: some View {
        if let display = entry.display {
            switch family {
            case .accessoryInline:
                Text(verbatim: inlineText(display))
            case .accessoryCircular:
                VStack(spacing: 0) {
                    Image(systemName: WatchWording.icon(display.phase)).font(.caption2).accessibilityHidden(true)
                    Text(verbatim: WatchWording.headlineTime(display) ?? display.flight.dep)
                        .font(.system(.caption, design: .rounded).monospacedDigit())
                        .minimumScaleFactor(0.6)
                }
                .widgetAccentable()
            case .accessoryCorner:
                Image(systemName: WatchWording.icon(display.phase))
                    .widgetAccentable()
                    .widgetLabel { Text(verbatim: inlineText(display)) }
            default:
                VStack(alignment: .leading, spacing: 1) {
                    HStack {
                        Text(verbatim: display.flight.designator).font(.headline).widgetAccentable()
                        Spacer(minLength: 2)
                        if let time = WatchWording.headlineTime(display) {
                            Text(verbatim: time).font(.headline.monospacedDigit())
                        }
                    }
                    Text(verbatim: "\(display.flight.dep) → \(display.flight.arr) · \(WatchWording.status(display))")
                        .font(.caption)
                        .lineLimit(1)
                    if let gate = WatchWording.gate(display) {
                        Text(gate).font(.caption).lineLimit(1)
                    }
                }
            }
        } else {
            switch family {
            case .accessoryInline:
                Text(String(localized: "No upcoming flights"))
            case .accessoryCircular, .accessoryCorner:
                Image(systemName: "airplane").accessibilityLabel(String(localized: "No upcoming flights"))
            default:
                VStack(alignment: .leading) {
                    Text(String(localized: "No upcoming flights")).font(.headline)
                    Text(String(localized: "Track one in HeyGate")).font(.caption)
                }
            }
        }
    }

    private func inlineText(_ display: WidgetDisplay) -> String {
        [display.flight.designator, WatchWording.headlineTime(display), WatchWording.gate(display) ?? WatchWording.status(display)]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}
