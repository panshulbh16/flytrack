import FlyTrackCore
import SwiftUI

// Shared by the watch app and its complication (project.yml lists this file in both targets). Colours are the design
// brief's dark (Night) tokens: watch faces are always dark.

enum WatchColor {
    static let ink = Color(red: 0xF6 / 255, green: 0xF4 / 255, blue: 0xE7 / 255)
    static let muted = Color(red: 0xC0 / 255, green: 0xCD / 255, blue: 0xBB / 255)
    static let accent = Color(red: 0xE8 / 255, green: 0x8A / 255, blue: 0x62 / 255)
    static let onTime = Color(red: 0xA9 / 255, green: 0xCF / 255, blue: 0xA0 / 255)
    static let delayed = Color(red: 0xE8 / 255, green: 0xC9 / 255, blue: 0x8A / 255)
    static let cancelled = Color(red: 0xF2 / 255, green: 0xBD / 255, blue: 0xA3 / 255)
    static let card = Color(red: 0x1F / 255, green: 0x3A / 255, blue: 0x2F / 255)
}

enum WatchWording {
    static func status(_ display: WidgetDisplay) -> String {
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

    static func color(_ display: WidgetDisplay) -> Color {
        switch display.phase {
        case .cancelled, .diverted: return WatchColor.cancelled
        case .boarding, .departed, .inAir: return WatchColor.accent
        case .landed, .checkIn, .upcoming: return display.lateMin != nil ? WatchColor.delayed : WatchColor.onTime
        }
    }

    /// Departure time before take-off, arrival time after.
    static func headlineTime(_ display: WidgetDisplay) -> String? {
        switch display.phase {
        case .upcoming, .checkIn, .boarding, .cancelled: return display.depTime
        case .departed, .inAir, .landed, .diverted: return display.arrTime
        }
    }

    static func gate(_ display: WidgetDisplay) -> String? {
        if let belt = display.belt { return String(localized: "Belt \(belt)") }
        switch (display.gate, display.terminal) {
        case let (gate?, terminal?): return String(localized: "Gate \(gate) · T\(terminal)")
        case let (gate?, nil): return String(localized: "Gate \(gate)")
        case let (nil, terminal?): return String(localized: "Terminal \(terminal)")
        case (nil, nil): return nil
        }
    }

    static func icon(_ phase: WidgetPhase) -> String {
        switch phase {
        case .upcoming, .checkIn: return "clock"
        case .boarding: return "figure.walk"
        case .departed, .inAir: return "airplane"
        case .landed: return "airplane.arrival"
        case .cancelled: return "xmark.circle"
        case .diverted: return "arrow.triangle.turn.up.right.diamond"
        }
    }
}

/// The payload the phone last sent, from the watch's App Group (the complication cannot talk to the phone itself).
enum WatchSnapshotStore {
    static var defaults: UserDefaults? { UserDefaults(suiteName: WatchContract.watchAppGroup) }

    static func load() -> WatchPayload? { WatchPayloadStorage.load(from: defaults) }

    static func save(_ text: String) { WatchPayloadStorage.save(text, in: defaults) }
}
