import FlyTrackCore
import MapKit
import SwiftUI

/// The flight map (P2 contract section 5): MapKit muted standard style, a geodesic route in terracotta (flown part
/// solid, the rest dashed), cream airport dots with an orange ring, and the plane rotated to its bearing. Offline,
/// MapKit has no tiles, but the route, the airports and the estimated plane still draw.
struct FlightMapView: View {
    let model: FlightMapModel
    /// Compact: the card on the detail screen, not interactive. Otherwise the full-screen map.
    var compact = false

    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $camera, interactionModes: compact ? [] : [.pan, .zoom]) {
            if model.flownEnds.count == 2 {
                MapPolyline(geodesic(model.flownEnds))
                    .stroke(FlyColor.mapRoute, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            }
            if model.remainingEnds.count == 2 {
                MapPolyline(geodesic(model.remainingEnds))
                    .stroke(FlyColor.mapRoute, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round, dash: [6, 6]))
            }
            Annotation(model.depCode, coordinate: coordinate(model.dep), anchor: .center) {
                AirportDot().accessibilityLabel(String(localized: "Departure airport \(model.depCity)"))
            }
            Annotation(model.arrCode, coordinate: coordinate(model.arr), anchor: .center) {
                AirportDot().accessibilityLabel(String(localized: "Arrival airport \(model.arrCity)"))
            }
            if let plane = model.plane {
                Annotation("", coordinate: coordinate(plane.point), anchor: .center) {
                    PlaneIcon(bearing: plane.bearingDeg, size: compact ? 22 : 28)
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll, showsTraffic: false))
        .mapControlVisibility(.hidden)
        .background(FlyColor.mapOcean)
        .overlay(alignment: .topLeading) {
            if let source = model.source {
                SourceChip(source: source).padding(10)
            }
        }
        .onAppear { camera = initialCamera() }
        .onChange(of: model.flightId) { camera = initialCamera() }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "Map of the route from \(model.depCity) to \(model.arrCity)"))
    }

    /// The whole route with a margin, at least a few hundred kilometres across for a short hop.
    private func initialCamera() -> MapCameraPosition {
        let route = geodesic([model.dep, model.arr])
        var rect = route.boundingMapRect
        if let plane = model.plane {
            let point = MKMapPoint(coordinate(plane.point))
            rect = rect.union(MKMapRect(origin: point, size: MKMapSize(width: 1, height: 1)))
        }
        let minimum = MKMapPointsPerMeterAtLatitude(coordinate(model.dep).latitude) * 300_000
        let width = max(rect.size.width, minimum)
        let height = max(rect.size.height, minimum)
        let centred = MKMapRect(x: rect.midX - width / 2, y: rect.midY - height / 2, width: width, height: height)
        let margin = compact ? 0.35 : 0.25
        return .rect(centred.insetBy(dx: -width * margin, dy: -height * margin))
    }

    private func geodesic(_ points: [GeoPoint]) -> MKGeodesicPolyline {
        let coordinates = points.map(coordinate)
        return MKGeodesicPolyline(coordinates: coordinates, count: coordinates.count)
    }

    private func coordinate(_ point: GeoPoint) -> CLLocationCoordinate2D {
        var lon = point.lon.truncatingRemainder(dividingBy: 360)
        if lon > 180 { lon -= 360 }
        if lon < -180 { lon += 360 }
        return CLLocationCoordinate2D(latitude: point.lat, longitude: lon)
    }
}

/// Cream dot with an orange ring.
private struct AirportDot: View {
    var body: some View {
        Circle()
            .fill(FlyColor.mapAirport)
            .frame(width: 12, height: 12)
            .overlay(Circle().stroke(FlyColor.mapRoute, lineWidth: 2.5))
    }
}

/// The SF Symbol `airplane` points east (90°), so it turns by bearing − 90. The map never rotates
/// (no `.rotate` interaction), so north stays up and the bearing needs no correction.
struct PlaneIcon: View {
    var bearing: Double
    var size: CGFloat = 28

    var body: some View {
        Image(systemName: "airplane")
            .font(.system(size: size * 0.62, weight: .semibold))
            .foregroundStyle(FlyColor.mapPlane)
            .rotationEffect(.degrees(bearing - 90))
            .frame(width: size, height: size)
            .background(Circle().fill(FlyColor.paper.opacity(0.9)))
            .overlay(Circle().stroke(FlyColor.mapRoute, lineWidth: 1.5))
            .accessibilityLabel(String(localized: "Plane, heading \(Int(bearing.rounded())) degrees"))
    }
}

/// "Live" (a fix younger than 20 min) or "Estimated" (contract section 5). Estimated uses the outline style and
/// the italic serif label of docs/design-brief.md.
struct SourceChip: View {
    var source: PositionSource

    var body: some View {
        Group {
            switch source {
            case .live:
                Label(String(localized: "Live"), systemImage: "dot.radiowaves.left.and.right")
                    .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                    .foregroundStyle(FlyColor.accent)
            case .estimated:
                Text(String(localized: "Estimated"))
                    .font(FlyFont.serifItalic(14, relativeTo: .caption))
                    .foregroundStyle(FlyColor.muted)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(FlyColor.paper.opacity(0.92), in: Capsule())
        .overlay(Capsule().stroke(source == .live ? FlyColor.accentLine : FlyColor.outline, lineWidth: 1))
        .accessibilityLabel(source == .live ? String(localized: "Live position") : String(localized: "Estimated position"))
    }
}

/// Progress line and remaining time, the same estimate as the map (card, detail and map panel).
struct FlightProgressLine: View {
    var progress: Double
    var source: PositionSource?
    var remaining: TimeInterval?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ProgressView(value: min(1, max(0, progress)))
                .tint(FlyColor.accentLine)
                .accessibilityLabel(String(localized: "Flight progress"))
                .accessibilityValue(String(localized: "\(Int((progress * 100).rounded())) percent"))
            HStack(spacing: 8) {
                if let remaining, remaining > 0 {
                    Text(String(localized: "Lands in \(remainingText(remaining))"))
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .monospacedDigit()
                }
                Spacer(minLength: 0)
                if let source { SourceChip(source: source) }
            }
        }
    }
}

/// "1 hr 20 min", "45 min".
func remainingText(_ interval: TimeInterval) -> String {
    let minutes = max(0, Int((interval / 60).rounded(.up)))
    let hours = minutes / 60
    let rest = minutes % 60
    if hours == 0 { return String(localized: "\(rest) min") }
    if rest == 0 { return String(localized: "\(hours) hr") }
    return String(localized: "\(hours) hr \(rest) min")
}

/// The map card on the detail screen; a tap opens the full map.
struct FlightMapCard: View {
    let model: FlightMapModel
    @State private var fullScreen = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                fullScreen = true
            } label: {
                FlightMapView(model: model, compact: true)
                    .allowsHitTesting(false) // the whole card is the button
                    .frame(height: 200)
                    .clipShape(.rect(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(FlyColor.outlineVariant, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityHint(String(localized: "Opens the full map"))
            FlightMapPanel(model: model)
        }
        .fullScreenCover(isPresented: $fullScreen) {
            FullFlightMap(model: model) { fullScreen = false }
        }
    }
}

/// What the plane is doing, under the map.
struct FlightMapPanel: View {
    let model: FlightMapModel

    var body: some View {
        switch model.state {
        case .airborne:
            FlightProgressLine(progress: model.progress ?? 0, source: model.source, remaining: model.remaining)
        case .notDeparted:
            mapNote(String(localized: "Not departed yet. The plane appears once it takes off."))
        case .arrived:
            mapNote(String(localized: "Arrived in \(model.arrCity)."))
        case .cancelled:
            mapNote(String(localized: "This flight was cancelled."))
        case .positionUnknown:
            mapNote(String(localized: "Position not known right now."))
        }
    }

    private func mapNote(_ text: String) -> some View {
        Text(text)
            .font(FlyFont.sans(13, relativeTo: .footnote))
            .foregroundStyle(FlyColor.muted)
    }
}

private struct FullFlightMap: View {
    let model: FlightMapModel
    var close: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                FlightMapView(model: model, compact: false)
                    .ignoresSafeArea(edges: .bottom)
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(model.depCode) → \(model.arrCode)")
                        .font(FlyFont.numeral(28))
                        .accessibilityLabel(String(localized: "\(model.depCity) to \(model.arrCity)"))
                    FlightMapPanel(model: model)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(FlyColor.paper)
            }
            .navigationTitle(model.designator)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Close"), action: close)
                }
            }
        }
        .foregroundStyle(FlyColor.ink)
    }
}
