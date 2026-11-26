import FlyTrackCore
import SwiftUI
import UIKit

/// Passport and Year in Review (P2 contract section 2). Overview: all time for Pro, the last 12 months for free.
/// Year in Review: the current year, free for everyone. Both can be shared as an image (ImageRenderer).
struct PassportScreen: View {
    @ObservedObject var shell: AppShell
    var initialTab: PassportTab = .overview
    @State private var store: PassportStore?
    @State private var palette = CardPalette.light
    @State private var shareItem: ShareImageItem?
    @State private var shareFailed = false

    var body: some View {
        PaperScreen {
            if let store {
                content(store)
            } else {
                ProgressView(String(localized: "Loading…"))
            }
        }
        .navigationTitle(String(localized: "Passport"))
        .sheet(item: $shareItem) { item in
            ImageActivitySheet(image: item.image, text: item.text).ignoresSafeArea()
        }
        .alert(String(localized: "Couldn't share the card. Try again."), isPresented: $shareFailed) {
            Button(String(localized: "OK"), role: .cancel) {}
        }
        .task {
            if store == nil {
                store = PassportStore(model: PassportModel(source: shell.passport, clock: shell.clock, tab: initialTab))
            }
            await store?.load()
        }
    }

    @ViewBuilder
    private func content(_ store: PassportStore) -> some View {
        let state = store.state
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Picker(String(localized: "Passport view"), selection: Binding(
                    get: { state.tab },
                    set: { tab in Task { await store.select(tab) } }
                )) {
                    Text(overviewTabTitle(state)).tag(PassportTab.overview)
                    Text(String(localized: "\(String(state.currentYear)) in review")).tag(PassportTab.year)
                }
                .pickerStyle(.segmented)

                if let passport = state.passport {
                    passportBody(passport, state: state)
                } else if state.isLoading {
                    ProgressView(String(localized: "Loading…")).frame(maxWidth: .infinity)
                } else if let problem = state.problem {
                    problemView(problem, store: store)
                }
            }
            .padding(20)
        }
        .refreshable { await store.refresh() }
    }

    private func overviewTabTitle(_ state: PassportState) -> String {
        if state.passport.map({ $0.scope == .all }) == true || shell.pro.state.isPro {
            return String(localized: "All time")
        }
        return String(localized: "Last 12 months")
    }

    @ViewBuilder
    private func passportBody(_ passport: Passport, state: PassportState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(heading(passport))
                .font(FlyFont.serif(34, relativeTo: .largeTitle))
                .accessibilityAddTraits(.isHeader)
            if let first = passport.firstFlightDate {
                Text(String(localized: "First flight \(FlightTimeFormat.fullDate(first))"))
                    .font(FlyFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(FlyColor.muted)
            }
        }
        if let problem = state.problem {
            Text(problemText(problem)).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
        }
        if passport.isEmpty {
            Text(passport.scope == .year
                 ? String(localized: "Nothing flown in \(String(passport.year)) yet. Flights you track show up here once they take off.")
                 : String(localized: "Flights you track show up here once they take off."))
                .font(FlyFont.sans(16, relativeTo: .body))
        } else {
            statsGrid(passport)
            if let laps = passport.earthLaps {
                Text(String(localized: "That's \(laps.formatted(.number.precision(.fractionLength(1)))) times around the Earth"))
                    .font(FlyFont.serifItalic(20, relativeTo: .title3))
                    .foregroundStyle(FlyColor.accent)
            }
            MonthChart(bars: state.bars)
            highlights(passport)
            countedList(String(localized: "Airports"), passport.airports.prefix(passportListLimit).map { ($0.iata, $0.count) })
            countedList(String(localized: "Airlines"), passport.airlines.prefix(passportListLimit).map { ($0.name ?? $0.carrier, $0.count) })
            countedList(String(localized: "Aircraft"), passport.aircraft.prefix(passportListLimit).map { ($0.model, $0.count) })
            if !passport.countries.isEmpty {
                section(String(localized: "Countries")) {
                    Text(passport.countries.map(countryName).joined(separator: " · "))
                        .font(FlyFont.sans(15, relativeTo: .body))
                }
            }
            section(String(localized: "Carbon footprint")) {
                Text(String(localized: "About \(passport.co2Kg.formatted()) kg CO₂"))
                    .font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                    .monospacedDigit()
                Text(String(localized: "Estimate for one economy seat, from the distance flown. Real emissions depend on the aircraft, the cabin and how full the flight was."))
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.muted)
            }
            if let card = state.shareCard {
                shareSection(card)
            }
        }
        if passport.proLimited {
            VStack(alignment: .leading, spacing: 6) {
                Text(String(localized: "Your full history is in Pro"))
                    .font(FlyFont.serifItalic(20, relativeTo: .title3))
                Text(String(localized: "Free shows your last 12 months and this year's review. HeyGate Pro counts every flight you have ever tracked."))
                    .font(FlyFont.sans(14, relativeTo: .subheadline))
                Button(String(localized: "See plans")) { shell.path.append(Route.paywall) }
                    .hitTarget()
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
        }
    }

    private func heading(_ passport: Passport) -> String {
        switch passport.scope {
        case .all: String(localized: "Your passport")
        case .last12: String(localized: "Your last 12 months")
        case .year: String(localized: "\(String(passport.year)) in the air")
        }
    }

    private func statsGrid(_ passport: Passport) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], spacing: 14) {
            ForEach(passport.statTiles, id: \.kind) { tile in
                VStack(alignment: .leading, spacing: 2) {
                    Text(tile.value.formatted())
                        .font(FlyFont.numeral(32))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(statLabel(tile.kind).uppercased())
                        .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(FlyColor.muted)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    @ViewBuilder
    private func highlights(_ passport: Passport) -> some View {
        section(String(localized: "Highlights")) {
            if let longest = passport.longest {
                highlightRow(String(localized: "Longest flight"),
                             "\(longest.carrier) \(longest.number) · \(longest.dep) → \(longest.arr) · \(longest.distanceKm.formatted()) km")
            }
            if let route = passport.topRoute {
                highlightRow(String(localized: "Most flown route"), String(localized: "\(route.dep) → \(route.arr), \(route.count) times"))
            }
            if let onTime = passport.delay.onTimePct {
                highlightRow(String(localized: "Departed on time"), "\(onTime)%")
            }
            if passport.delay.totalMin > 0 {
                highlightRow(String(localized: "Time lost to delays"), durationText(minutes: passport.delay.totalMin))
            }
            if let worst = passport.delay.worst {
                highlightRow(String(localized: "Longest delay"), "\(worst.carrier) \(worst.number) · \(durationText(minutes: worst.depDelayMin))")
            }
        }
    }

    private func highlightRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.muted)
            Spacer(minLength: 12)
            Text(value).font(FlyFont.sans(15, weight: .medium, relativeTo: .body)).monospacedDigit().multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func countedList(_ title: String, _ items: [(String, Int)]) -> some View {
        if !items.isEmpty {
            section(title) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    highlightRow(item.0, String(localized: "\(item.1)×"))
                }
            }
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                .tracking(1.2)
                .accessibilityAddTraits(.isHeader)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func shareSection(_ card: YearInReviewCardModel) -> some View {
        section(card.scope == .year ? String(localized: "Year in review") : String(localized: "Share your passport")) {
            ScaledShareCard(model: card, palette: palette)
                .frame(maxWidth: shareCardSize.width)
                .clipShape(.rect(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(FlyColor.outlineVariant, lineWidth: 1))
                .frame(maxWidth: .infinity)
            Picker(String(localized: "Card style"), selection: $palette) {
                Text(String(localized: "Paper")).tag(CardPalette.light)
                Text(String(localized: "Night")).tag(CardPalette.night)
            }
            .pickerStyle(.segmented)
            Button {
                share(card)
            } label: {
                Label(card.scope == .year ? String(localized: "Share my year") : String(localized: "Share my passport"),
                      systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(FlyColor.paper)
            .hitTarget()
        }
    }

    private func share(_ card: YearInReviewCardModel) {
        guard let image = renderShareCard(card, palette: palette) else {
            shareFailed = true
            return
        }
        shareItem = ShareImageItem(image: image, text: shareText(card))
    }

    private func problemView(_ problem: PassportProblem, store: PassportStore) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Couldn't load your passport"))
                .font(FlyFont.serifItalic(22, relativeTo: .title2))
            Text(problemText(problem)).font(FlyFont.sans(15, relativeTo: .body))
            Button(String(localized: "Try again")) { Task { await store.refresh() } }
                .hitTarget()
        }
    }

    private func problemText(_ problem: PassportProblem) -> String {
        switch problem {
        case .offline: String(localized: "You're offline.")
        case .notConfigured: String(localized: "Backend not configured.")
        case .failed: String(localized: "That didn't work. Try again.")
        }
    }
}

// MARK: - month chart

private struct MonthChart: View {
    let bars: [MonthBar]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Flights by month").uppercased())
                .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                .tracking(1.2)
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(bars, id: \.month) { bar in
                    VStack(spacing: 4) {
                        if bar.flights > 0 {
                            Text("\(bar.flights)").font(FlyFont.sans(11, relativeTo: .caption2)).monospacedDigit()
                        }
                        RoundedRectangle(cornerRadius: 2)
                            .fill(bar.flights == 0 ? FlyColor.outlineVariant : FlyColor.accentLine)
                            .frame(height: bar.flights == 0 ? 2 : 6 + 70 * bar.fraction)
                        Text(monthInitial(bar.month))
                            .font(FlyFont.sans(11, relativeTo: .caption2))
                            .foregroundStyle(FlyColor.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(monthName(bar.month)): \(bar.flights)")
                }
            }
            .frame(height: 110, alignment: .bottom)
        }
    }
}

// MARK: - share card

/// Fixed palettes of the share card (docs/design-brief.md): HeyTurbulence's paper card and its "Night" card. The
/// card keeps its own colours so the image looks the same whatever the app theme. Same values as Android.
struct CardPalette: Hashable {
    var paper: Color
    var ink: Color
    var muted: Color
    var hairline: Color
    var decoration: Color
    var accentText: Color
    private var name: String

    static let light = CardPalette(
        paper: hex(0xF6F5EF), ink: hex(0x244735), muted: hex(0x5A6C5A),
        hairline: hex(0xD6DCCF), decoration: hex(0xC86842), accentText: hex(0xA34E2E), name: "light"
    )
    static let night = CardPalette(
        paper: hex(0x182E25), ink: hex(0xF6F4E7), muted: hex(0xC0CDBB),
        hairline: hex(0x4C6450), decoration: hex(0xDF754E), accentText: hex(0xE88A62), name: "night"
    )

    static func == (lhs: CardPalette, rhs: CardPalette) -> Bool { lhs.name == rhs.name }
    func hash(into hasher: inout Hasher) { hasher.combine(name) }

    private static func hex(_ value: UInt32) -> Color {
        Color(red: Double(value >> 16 & 0xFF) / 255, green: Double(value >> 8 & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}

/// Share card proportions (portrait 4:5, fine for Instagram and WhatsApp). Drawn for a 360-point width.
let shareCardSize = CGSize(width: 360, height: 450)

/// Adapted from HeyTurbulence's share card: caption row, hairline, big serif title, the little numbers, a by-month
/// strip and three highlights. Fixed sizes (not Dynamic Type) so the shared image never overflows.
struct ShareCardView: View {
    let model: YearInReviewCardModel
    let palette: CardPalette

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(verbatim: "HEYGATE")
                    .font(.custom("DM Sans", fixedSize: 9).weight(.medium))
                    .tracking(1.4)
                    .foregroundStyle(palette.ink)
                Spacer()
                Text(caption.uppercased())
                    .font(.custom("DM Sans", fixedSize: 8))
                    .tracking(1.2)
                    .foregroundStyle(palette.muted)
            }
            Spacer().frame(height: 9)
            Rectangle().fill(palette.hairline).frame(height: 1)
            Spacer().frame(height: 14)
            Text(titleHead)
                .font(.custom("Instrument Serif", fixedSize: model.scope == .year ? 64 : 46))
                .foregroundStyle(palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text(titleTail)
                .font(.custom("Instrument Serif", fixedSize: 30).italic())
                .foregroundStyle(palette.accentText)
            Spacer().frame(height: 16)
            HStack(alignment: .top, spacing: 12) {
                number(model.flights.formatted(), String(localized: "Flights")).frame(maxWidth: .infinity, alignment: .leading)
                number(model.distanceKm.formatted(), String(localized: "Km flown")).frame(maxWidth: .infinity, alignment: .leading).layoutPriority(1)
                number(model.hours.formatted(), String(localized: "Hours in the sky")).frame(maxWidth: .infinity, alignment: .leading)
            }
            Spacer().frame(height: 10)
            HStack(alignment: .top, spacing: 12) {
                number(model.airports.formatted(), String(localized: "Airports")).frame(maxWidth: .infinity, alignment: .leading)
                number(model.countries.formatted(), String(localized: "Countries")).frame(maxWidth: .infinity, alignment: .leading)
                Color.clear.frame(maxWidth: .infinity)
            }
            Spacer().frame(height: 14)
            strip
            Spacer(minLength: 8)
            if let route = model.topRoute {
                highlight(String(localized: "Top route"), String(localized: "\(route.dep) → \(route.arr), \(route.count) times"))
            }
            if let longest = model.longest {
                highlight(String(localized: "Longest"), "\(longest.dep) → \(longest.arr) · \(longest.distanceKm.formatted()) km")
            }
            if let airline = model.topAirline {
                highlight(String(localized: "Flew most with"), airline)
            }
            Spacer().frame(height: 10)
            Rectangle().fill(palette.hairline).frame(height: 1)
            Spacer().frame(height: 8)
            Text(String(localized: "Tracked with HeyGate"))
                .font(.custom("DM Sans", fixedSize: 8.5))
                .foregroundStyle(palette.muted)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 22)
        .frame(width: shareCardSize.width, height: shareCardSize.height, alignment: .topLeading)
        .background(palette.paper)
        .monospacedDigit()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(shareText(model))
    }

    private var caption: String {
        switch model.scope {
        case .year: String(localized: "Year in review")
        case .all: String(localized: "Passport")
        case .last12: String(localized: "Last 12 months")
        }
    }

    private var titleHead: String {
        switch model.scope {
        case .year: String(model.year)
        case .all: String(localized: "My passport")
        case .last12: String(localized: "My year")
        }
    }

    private var titleTail: String { String(localized: "in the air") }

    private func number(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(.custom("DM Sans", fixedSize: 24))
                .tracking(-0.5)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label.uppercased())
                .font(.custom("DM Sans", fixedSize: 7.5))
                .tracking(1)
                .foregroundStyle(palette.muted)
                .lineLimit(1)
        }
    }

    private var strip: some View {
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(model.bars, id: \.month) { bar in
                VStack(spacing: 3) {
                    UnevenRoundedRectangle(topLeadingRadius: 2, topTrailingRadius: 2)
                        .fill(bar.flights == 0 ? palette.hairline : palette.decoration)
                        .frame(width: 11, height: bar.flights == 0 ? 1.5 : 4 + 24 * bar.fraction)
                    Text(monthInitial(bar.month))
                        .font(.custom("DM Sans", fixedSize: 7))
                        .foregroundStyle(palette.muted)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 42, alignment: .bottom)
    }

    private func highlight(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label.uppercased())
                .font(.custom("DM Sans", fixedSize: 7.5))
                .tracking(1)
                .foregroundStyle(palette.muted)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(value)
                .font(.custom("DM Sans", fixedSize: 11))
                .foregroundStyle(palette.ink)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .padding(.vertical, 2)
    }
}

/// The card scaled down to the width available (the image itself is always drawn at 360 × 450 points).
private struct ScaledShareCard: View {
    let model: YearInReviewCardModel
    let palette: CardPalette

    var body: some View {
        GeometryReader { geometry in
            ShareCardView(model: model, palette: palette)
                .scaleEffect(min(1, geometry.size.width / shareCardSize.width), anchor: .topLeading)
        }
        .aspectRatio(shareCardSize.width / shareCardSize.height, contentMode: .fit)
    }
}

/// The card as a 3× PNG-ready image. ImageRenderer must run on the main actor.
@MainActor
func renderShareCard(_ model: YearInReviewCardModel, palette: CardPalette) -> UIImage? {
    let renderer = ImageRenderer(content: ShareCardView(model: model, palette: palette).environment(\.colorScheme, .light))
    renderer.scale = 3
    renderer.proposedSize = ProposedViewSize(shareCardSize)
    return renderer.uiImage
}

private func shareText(_ model: YearInReviewCardModel) -> String {
    let flights = model.flights.formatted()
    let km = model.distanceKm.formatted()
    switch model.scope {
    case .year: return String(localized: "My \(String(model.year)) in the air: \(flights) flights, \(km) km. Tracked with HeyGate.")
    case .all: return String(localized: "My HeyGate passport: \(flights) flights, \(km) km.")
    case .last12: return String(localized: "My last 12 months in the air: \(flights) flights, \(km) km. Tracked with HeyGate.")
    }
}

private func statLabel(_ kind: StatKind) -> String {
    switch kind {
    case .flights: String(localized: "Flights")
    case .distanceKm: String(localized: "Km flown")
    case .hours: String(localized: "Hours in the sky")
    case .airports: String(localized: "Airports")
    case .countries: String(localized: "Countries")
    case .airlines: String(localized: "Airlines")
    }
}

/// "1 hr 5 min", "45 min" for delay totals.
private func durationText(minutes: Int) -> String {
    let hours = minutes / 60
    let rest = minutes % 60
    if hours == 0 { return String(localized: "\(rest) min") }
    if rest == 0 { return String(localized: "\(hours) hr") }
    return String(localized: "\(hours) hr \(rest) min")
}

private func calendarDate(_ month: YearMonth) -> Date {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: passportTimeZone) ?? .current
    return calendar.date(from: DateComponents(year: month.year, month: month.month, day: 15)) ?? Date()
}

/// "J", "F", … in the phone's language.
private func monthInitial(_ month: YearMonth) -> String {
    calendarDate(month).formatted(.dateTime.month(.narrow))
}

private func monthName(_ month: YearMonth) -> String {
    calendarDate(month).formatted(.dateTime.month(.wide).year())
}

private func countryName(_ code: String) -> String {
    Locale.current.localizedString(forRegionCode: code) ?? code
}

private struct ShareImageItem: Identifiable {
    let id = UUID()
    let image: UIImage
    let text: String
}

/// The system share sheet with the card image and a line of text.
private struct ImageActivitySheet: UIViewControllerRepresentable {
    let image: UIImage
    let text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [image, text], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

@MainActor
@Observable
final class PassportStore {
    var state: PassportState
    let model: PassportModel
    init(model: PassportModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load(); state = model.state }
    func refresh() async { await model.refresh(); state = model.state }
    func select(_ tab: PassportTab) async { await model.select(tab); state = model.state }
}
