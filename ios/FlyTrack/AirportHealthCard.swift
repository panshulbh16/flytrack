import FlyTrackCore
import SwiftUI

/// Airport Health on the flight detail screen (P2 contract section 1): a level word and a localised headline for
/// everyone, delay numbers and the METAR summary for Pro.
struct AirportHealthCard: View {
    let rows: [AirportHealthRow]
    /// Free users see a way to Pro under the summary.
    var showProUpsell: Bool
    var openPaywall: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Airport conditions"))
                .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                .textCase(.uppercase)
                .tracking(1.2)
                .accessibilityAddTraits(.isHeader)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider().overlay(FlyColor.outlineVariant) }
                AirportHealthRowView(row: row)
            }
            if showProUpsell, rows.contains(where: { $0.health.detail == nil }) {
                HStack(alignment: .firstTextBaseline) {
                    Text(String(localized: "Pro adds average delays, cancellations and the weather report."))
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(FlyColor.muted)
                    Spacer(minLength: 8)
                    Button(String(localized: "See Pro"), action: openPaywall)
                        .font(FlyFont.sans(13, weight: .medium, relativeTo: .footnote))
                        .hitTarget()
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
    }
}

private struct AirportHealthRowView: View {
    let row: AirportHealthRow

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: row.iata).font(FlyFont.sans(15, weight: .medium, relativeTo: .headline))
                Text(row.city).font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.muted)
                Spacer(minLength: 8)
                LevelPill(level: row.health.level)
            }
            Text(headlineText(row.health))
                .font(FlyFont.serifItalic(19, relativeTo: .title3))
                .foregroundStyle(FlyColor.tone(row.health.level.tone))
            if let detail = row.health.detail {
                detailLines(detail)
            }
            if let updated = row.health.updatedAt {
                Text(String(localized: "Updated \(updated.formatted(date: .omitted, time: .shortened))"))
                    .font(FlyFont.sans(12, relativeTo: .caption))
                    .foregroundStyle(FlyColor.muted)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder
    private func detailLines(_ detail: HealthDetail) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if detail.hasDelaySample {
                if let average = detail.avgDepDelayMin {
                    line(String(localized: "Departures in the last 3 hours: \(average) min late on average"))
                }
                if let share = detail.delayedPct {
                    line(String(localized: "\(share)% of departures delayed"))
                }
            } else {
                line(String(localized: "Too few departures in the last 3 hours to judge delays."))
            }
            if detail.cancelledToday > 0 {
                line(String(localized: "\(detail.cancelledToday) cancelled today"))
            }
            if let weather = detail.weather {
                line(weatherLine(weather))
            }
        }
    }

    private func line(_ text: String) -> some View {
        Text(text).font(FlyFont.sans(14, relativeTo: .subheadline)).monospacedDigit()
    }

    private var accessibilityText: String {
        var parts = ["\(row.city) \(row.iata)", levelWord(row.health.level), headlineText(row.health)]
        if let detail = row.health.detail, let weather = detail.weather { parts.append(weatherLine(weather)) }
        return parts.joined(separator: ", ")
    }
}

private struct LevelPill: View {
    let level: HealthLevel

    var body: some View {
        Text(levelWord(level))
            .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
            .foregroundStyle(FlyColor.tone(level.tone))
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .overlay(Capsule().stroke(FlyColor.tone(level.tone), lineWidth: 1))
    }
}

private func levelWord(_ level: HealthLevel) -> String {
    switch level {
    case .normal: String(localized: "Normal")
    case .busy: String(localized: "Busy")
    case .disrupted: String(localized: "Disrupted")
    case .severe: String(localized: "Severe")
    }
}

/// The server's headline in the UI language; an unknown sentence is shown as sent (English).
func headlineText(_ health: AirportHealth) -> String {
    switch health.typedHeadline {
    case .normal: String(localized: "Operating normally")
    case .fog(let metres?): String(localized: "Fog: visibility \(metres) m")
    case .fog(.none): String(localized: "Fog")
    case .thunderstorms: String(localized: "Thunderstorms at the airport")
    case .lowVisibility(let metres): String(localized: "Low visibility: \(metres) m")
    case .lowCloud(let feet): String(localized: "Low cloud: \(feet) ft")
    case .poorWeather: String(localized: "Poor flying weather")
    case .cancellations(let count): String(localized: "\(count) flights cancelled today")
    case .runningLate(let minutes): String(localized: "Departures running \(minutes) min late")
    case .delayedShare(let percent): String(localized: "\(percent)% of departures delayed")
    case .other(let text): text.isEmpty ? levelWord(health.level) : text
    }
}

private func weatherLine(_ weather: AirportWeather) -> String {
    var parts: [String] = []
    if let word = presentWeather(weather.wx) {
        parts.append(weatherWord(word))
    } else if let wx = weather.wx {
        parts.append(wx)
    }
    if let metres = weather.visibilityM {
        parts.append(metres >= 9999 ? String(localized: "visibility 10 km or more") : String(localized: "visibility \(metres) m"))
    }
    if let wind = weather.windKts {
        parts.append(String(localized: "wind \(wind) kt"))
    }
    if let category = weather.category {
        parts.append(category.rawValue)
    }
    return parts.isEmpty ? String(localized: "No weather report") : parts.joined(separator: " · ")
}

private func weatherWord(_ weather: PresentWeather) -> String {
    switch weather {
    case .thunderstorm: String(localized: "Thunderstorm")
    case .fog: String(localized: "Fog")
    case .smoke: String(localized: "Smoke")
    case .dust: String(localized: "Dust")
    case .haze: String(localized: "Haze")
    case .mist: String(localized: "Mist")
    case .rain: String(localized: "Rain")
    }
}
