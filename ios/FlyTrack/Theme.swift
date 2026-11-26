import FlyTrackCore
import SwiftUI
import UIKit

/// HeyTurbulence's palette from docs/design-brief.md. Every token resolves per appearance (light, dark).
enum FlyColor {
    /// Screen background. Also the text colour on `primary` fills.
    static let paper = adaptive(0xF6F5EF, 0x182E25)
    /// List rows and cards.
    static let paperRaised = adaptive(0xFBFAF5, 0x1F3A2F)
    static let ink = adaptive(0x244735, 0xF6F4E7)
    static let muted = adaptive(0x5A6C5A, 0xC0CDBB)
    /// App tint: forest green in light, sage in dark.
    static let primary = adaptive(0x244735, 0xA0B99F)
    /// Terracotta for 11 pt text: 5.2:1 on paper in light (the brand #C86842 is only 3.5:1), 4.8:1 on cards in dark.
    static let accent = adaptive(0xA34E2E, 0xE88A62)
    /// Brand terracotta for rings, rules and route arrows. Not for small text in light (3.5:1 on paper).
    static let accentLine = adaptive(0xC86842, 0xDF754E)
    static let onTime = adaptive(0x4C7B41, 0xA9CFA0)
    static let delayed = adaptive(0x8D542D, 0xE8C98A)
    static let cancelled = adaptive(0xA34831, 0xF2BDA3)
    /// 1 pt borders that must be seen (3:1 on every surface): the "Estimated" chip, progress tracks.
    static let outline = adaptive(0x768775, 0x7A927E)
    /// Decorative hairlines only.
    static let outlineVariant = adaptive(0xD6DCCF, 0x4C6450)

    // Map (docs/design-brief.md "Map"; same values as Android's FlightMapStyle). Dark uses the Night palette.
    static let mapOcean = adaptive(0xE2E9DF, 0x253E36)
    static let mapLand = adaptive(0x3E6B56, 0x8CA890)
    static let mapGraticule = adaptive(0xC2D0C1, 0x466055)
    /// Great-circle route: solid where flown, dashed for the rest.
    static let mapRoute = adaptive(0xDF754E, 0xDF754E)
    /// Airport dots: cream with an orange (`mapRoute`) ring.
    static let mapAirport = adaptive(0xF6F5EF, 0xF6F5EF)
    static let mapPlane = adaptive(0x244735, 0xF6F4E7)

    static func tone(_ tone: FlightStatusTone) -> Color {
        switch tone {
        case .onTime: return onTime
        case .delayed: return delayed
        case .cancelled: return cancelled
        case .active: return accent
        case .estimated: return muted
        }
    }

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        func rgb(_ hex: UInt32) -> UIColor {
            UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
        return Color(UIColor { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) })
    }
}

/// The appearance picked in Settings, stored in UserDefaults. `system` follows the phone.
enum Appearance: String, CaseIterable {
    case system, light, dark

    static let storageKey = "appearance"

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum FlyFont {
    static func serif(_ size: CGFloat, relativeTo style: Font.TextStyle) -> Font {
        .custom("Instrument Serif", size: size, relativeTo: style)
    }

    static func serifItalic(_ size: CGFloat, relativeTo style: Font.TextStyle) -> Font {
        .custom("Instrument Serif", size: size, relativeTo: style).italic()
    }

    static func sans(_ size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle) -> Font {
        .custom("DM Sans", size: size, relativeTo: style).weight(weight)
    }

    static func numeral(_ size: CGFloat) -> Font {
        .custom("DM Sans", size: size, relativeTo: .largeTitle).monospacedDigit()
    }
}

struct HitTarget: ViewModifier {
    func body(content: Content) -> some View {
        content.frame(minWidth: 44, minHeight: 44)
    }
}

extension View {
    func hitTarget() -> some View { modifier(HitTarget()) }
}

struct PaperScreen<Content: View>: View {
    @ViewBuilder var content: () -> Content

    // The paper is a real view, not a background on `content`: a screen whose content is empty while it loads
    // (Add flight) otherwise renders nothing, stays white, and never runs its `.task`.
    var body: some View {
        ZStack(alignment: .top) {
            FlyColor.paper.ignoresSafeArea()
            content()
        }
        .foregroundStyle(FlyColor.ink)
    }
}
