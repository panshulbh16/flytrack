import FlyTrackCore
import SwiftUI

enum FlyColor {
    static let paper = Color(red: 0.965, green: 0.961, blue: 0.937)
    static let paperRaised = Color(red: 0.984, green: 0.980, blue: 0.961)
    static let ink = Color(red: 0.141, green: 0.278, blue: 0.208)
    static let moss = Color(red: 0.376, green: 0.447, blue: 0.376)
    static let terracotta = Color(red: 0.784, green: 0.408, blue: 0.259)
    static let hairline = Color(red: 0.839, green: 0.863, blue: 0.812)
    static let onTime = Color(red: 0.298, green: 0.482, blue: 0.255)
    static let delayed = Color(red: 0.553, green: 0.329, blue: 0.176)
    static let cancelled = Color(red: 0.639, green: 0.282, blue: 0.192)
    static let night = Color(red: 0.094, green: 0.180, blue: 0.145)
    static let nightInk = Color(red: 0.965, green: 0.957, blue: 0.906)

    static func tone(_ tone: FlightStatusTone, dark: Bool) -> Color {
        switch tone {
        case .onTime: return onTime
        case .delayed: return delayed
        case .cancelled: return cancelled
        case .active: return terracotta
        case .estimated: return moss
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
    @Environment(\.colorScheme) private var scheme
    @ViewBuilder var content: () -> Content

    // The paper is a real view, not a background on `content`: a screen whose content is empty while it loads
    // (Add flight) otherwise renders nothing and never runs its `.task`. Same fix as PR #6.
    var body: some View {
        ZStack(alignment: .top) {
            (scheme == .dark ? FlyColor.night : FlyColor.paper).ignoresSafeArea()
            content()
        }
        .foregroundStyle(scheme == .dark ? FlyColor.nightInk : FlyColor.ink)
    }
}
