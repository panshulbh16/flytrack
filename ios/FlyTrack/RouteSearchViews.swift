import FlyTrackCore
import SwiftUI

// P3 B5: the "By route" tab of Add flight (from / to pickers over the bundled airports, a date, an optional airline),
// and the airport picker it shares with nothing else yet.

enum AddFlightTab: String, CaseIterable, Hashable {
    case number, route
}

@MainActor
@Observable
final class RouteStore {
    var state: RouteSearchState
    let model: RouteSearchModel
    init(model: RouteSearchModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

struct RouteSearchSection: View {
    let store: RouteStore
    let airports: AirportList
    @State private var picking: RouteEnd?

    enum RouteEnd: String, Identifiable {
        case from, to
        var id: String { rawValue }
    }

    var body: some View {
        Section {
            airportButton(.from, code: store.state.from)
            HStack {
                Spacer()
                Button(String(localized: "Swap airports"), systemImage: "arrow.up.arrow.down") { store.model.swap() }
                    .labelStyle(.iconOnly)
                    .hitTarget()
                    .disabled(store.state.isTracking)
            }
            airportButton(.to, code: store.state.to)
            DatePicker(String(localized: "Date"), selection: Binding(
                get: { store.state.date.startOfDayUTC() },
                set: { store.model.setDate(FlightTimeFormat.localDate($0, tz: "UTC")) }
            ), in: store.state.firstSelectableDate.startOfDayUTC()...store.state.lastSelectableDate.startOfDayUTC(), displayedComponents: .date)
            TextField(String(localized: "Airline code (optional), e.g. 6E"), text: Binding(
                get: { store.state.airlineInput },
                set: { store.model.setAirline($0) }
            ))
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            if let error = store.state.formError {
                Text(formErrorText(error)).foregroundStyle(FlyColor.cancelled)
            }
            Button(String(localized: "Search departures")) { Task { await store.model.search() } }
                .hitTarget()
                .disabled(!store.state.canSearch)
            Text(String(localized: "A route search counts as two searches."))
                .font(FlyFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(FlyColor.muted)
            results
        }
        .listRowBackground(FlyColor.paperRaised)
        .sheet(item: $picking) { end in
            AirportPickerSheet(
                title: end == .from ? String(localized: "route.from", defaultValue: "From") : String(localized: "route.to", defaultValue: "To"),
                airports: airports,
                allowsAny: end == .to,
                pick: { iata in
                    if end == .from { store.model.setFrom(iata) } else { store.model.setTo(iata) }
                    picking = nil
                }
            )
        }
    }

    private func airportButton(_ end: RouteEnd, code: String?) -> some View {
        let label = end == .from ? String(localized: "route.from", defaultValue: "From") : String(localized: "route.to", defaultValue: "To")
        return Button { picking = end } label: {
            HStack {
                Text(label).foregroundStyle(FlyColor.muted)
                Spacer()
                if let code, let airport = airports[code] {
                    Text(verbatim: "\(airport.city) · \(code)").font(FlyFont.sans(17, weight: .medium, relativeTo: .body))
                } else if let code {
                    Text(verbatim: code).font(FlyFont.sans(17, weight: .medium, relativeTo: .body))
                } else {
                    Text(end == .from ? String(localized: "Choose airport") : String(localized: "Any airport"))
                        .foregroundStyle(FlyColor.muted)
                }
            }
        }
        .hitTarget()
        .disabled(store.state.isTracking)
        .accessibilityLabel(label)
        .accessibilityValue(code.flatMap { airports[$0]?.name ?? $0 } ?? String(localized: "Not chosen"))
    }

    @ViewBuilder
    private var results: some View {
        switch store.state.search {
        case .idle: EmptyView()
        case .searching: ProgressView(String(localized: "Searching…"))
        case .results(_, let legs):
            Text(String(localized: "\(legs.count) departures")).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            ForEach(legs, id: \.id) { leg in
                RouteResultRow(
                    leg: leg,
                    tracked: store.state.trackedFlightIds.contains(leg.id),
                    busy: store.state.trackingFlightId == leg.id,
                    notice: store.state.trackNotice?.flightId == leg.id ? store.state.trackNotice?.problem : nil,
                    track: { Task { await store.model.track(flightId: leg.id) } }
                )
            }
        case .notFound: Text(String(localized: "No departures found for that day."))
        case .rateLimited: Text(String(localized: "You've used today's searches. Linking Google raises the limit."))
        case .providerError: Text(String(localized: "The flight service did not answer. Try again in a moment."))
        case .offline: Text(String(localized: "You're offline."))
        case .notConfigured: Text(String(localized: "Backend not configured."))
        case .rejected: Text(String(localized: "The server rejected that search."))
        }
    }

    private func formErrorText(_ error: RouteFormError) -> String {
        switch error {
        case .missingFrom: String(localized: "Choose the airport you fly from.")
        case .sameAirports: String(localized: "From and to must be different airports.")
        case .badAirline: String(localized: "An airline code has two letters or digits, such as 6E.")
        }
    }
}

private struct RouteResultRow: View {
    let leg: Flight
    let tracked: Bool
    let busy: Bool
    let notice: TrackProblem?
    let track: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(leg.designator).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                Spacer()
                if let dep = leg.bestDeparture {
                    Text(FlightTimeFormat.time(dep, tz: leg.depTz)).font(FlyFont.numeral(20))
                }
            }
            Text(verbatim: "\(leg.depIata) → \(leg.arrIata)").font(FlyFont.numeral(17))
            if let airline = leg.airlineName { Text(airline).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted) }
            Button(tracked ? String(localized: "Open") : (busy ? String(localized: "Adding…") : String(localized: "Track")), action: track)
                .hitTarget()
                .disabled(busy)
            if let notice {
                Text(routeTrackText(notice)).foregroundStyle(FlyColor.cancelled)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private func routeTrackText(_ problem: TrackProblem) -> String {
    switch problem {
    case .limitReached: String(localized: "You're tracking a lot of flights. Remove some you no longer need, then try again.")
    case .offline: String(localized: "You're offline.")
    case .flightGone: String(localized: "That flight is no longer available. Search again.")
    case .notConfigured: String(localized: "Backend not configured.")
    case .invalidDetails, .detailsNotSaved, .failed: String(localized: "That didn't work. Try again.")
    }
}

/// Searchable list over the bundled airports. A blank query lists Indian airports.
struct AirportPickerSheet: View {
    let title: String
    let airports: AirportList
    /// "Any airport" row (for the optional destination).
    let allowsAny: Bool
    let pick: (String?) -> Void
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if allowsAny && query.isEmpty {
                    Button(String(localized: "Any airport")) { pick(nil) }.hitTarget()
                }
                ForEach(airports.search(query, limit: 40)) { airport in
                    Button { pick(airport.iata) } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(verbatim: airport.iata).font(FlyFont.numeral(17)).frame(width: 48, alignment: .leading)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: airport.city).font(FlyFont.sans(16, weight: .medium, relativeTo: .body))
                                Text(verbatim: "\(airport.name), \(airport.country)")
                                    .font(FlyFont.sans(13, relativeTo: .footnote))
                                    .foregroundStyle(FlyColor.muted)
                            }
                        }
                    }
                    .hitTarget()
                    .accessibilityLabel(Text(verbatim: "\(airport.city), \(airport.name), \(airport.iata)"))
                }
                if airports.isEmpty {
                    Text(String(localized: "The airport list could not be loaded.")).foregroundStyle(FlyColor.muted)
                }
            }
            .scrollContentBackground(.hidden)
            .background(FlyColor.paper)
            .searchable(text: $query, prompt: String(localized: "City, airport or code"))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel")) { dismiss() }
                }
            }
        }
    }
}
