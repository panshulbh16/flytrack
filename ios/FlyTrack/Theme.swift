import FlyTrackCore
import SwiftUI
import UIKit

/// "Midnight navy and platinum" from docs/design-brief.md. Every token resolves per appearance (light, dark).
enum FlyColor {
    /// Screen background. Also the text colour on `primary` fills.
    static let paper = adaptive(0xF4F2ED, 0x0E1625)
    /// List rows and cards.
    static let paperRaised = adaptive(0xFDFBF8, 0x152032)
    static let ink = adaptive(0x172849, 0xEFECE5)
    static let muted = adaptive(0x4F5D76, 0xAAB4C6)
    /// App tint: navy in light, platinum in dark.
    static let primary = adaptive(0x1B335F, 0xD3D7DE)
    /// Platinum dark enough for 11 pt text on paper (6.1:1).
    static let accent = adaptive(0x555B66, 0xD3D7DE)
    /// Decorative platinum for rings and rules. Never text: 1.8:1 on paper in light.
    static let accentLine = adaptive(0xB4B9C2, 0x9AA0AA)
    static let onTime = adaptive(0x2E6A4C, 0x8FCBA8)
    static let delayed = adaptive(0x8A5512, 0xE7BE7A)
    static let cancelled = adaptive(0x9E2B3B, 0xF0A2A9)

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
