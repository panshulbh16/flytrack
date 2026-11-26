import FlyTrackCore
import PhotosUI
import SwiftUI
import Vision
import VisionKit

/// Reads the barcode on a boarding pass (IATA BCBP) with the live camera, or from a photo,
/// and opens Add flight with the flight, date, booking reference and seat filled in.
struct ScanScreen: View {
    @ObservedObject var shell: AppShell
    @State private var legs: [BoardingPassLeg] = []
    @State private var choosing = false
    @State private var done = false
    @State private var photo: PhotosPickerItem?
    @State private var notice: String?

    // ponytail: read once per appearance; a camera permission denied mid-screen shows a dark preview until reopened.
    private let canScan = DataScannerViewController.isSupported && DataScannerViewController.isAvailable

    var body: some View {
        PaperScreen {
            VStack(alignment: .leading, spacing: 16) {
                if canScan {
                    BarcodeScanner { payload in read(payload) }
                        .frame(maxWidth: .infinity, minHeight: 320, maxHeight: 420)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(String(localized: "Camera view for the boarding pass barcode"))
                    Text(String(localized: "Hold the barcode on your boarding pass inside the frame."))
                        .font(FlyFont.sans(15, relativeTo: .body))
                } else {
                    Text(String(localized: "The camera can't scan on this device. Allow camera access in Settings, or choose a photo of the boarding pass."))
                        .font(FlyFont.sans(15, relativeTo: .body))
                }
                PhotosPicker(String(localized: "Choose a photo of the barcode"), selection: $photo, matching: .images)
                    .buttonStyle(.bordered)
                    .hitTarget()
                if let notice {
                    Text(notice).foregroundStyle(FlyColor.cancelled)
                }
                Spacer()
            }
            .padding(20)
        }
        .navigationTitle(String(localized: "Scan boarding pass"))
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task { await readPhoto(item) }
        }
        .confirmationDialog(String(localized: "This boarding pass has several flights. Choose one."), isPresented: $choosing, titleVisibility: .visible) {
            ForEach(legs, id: \.self) { leg in
                Button("\(leg.flight.display)  \(leg.fromIata) → \(leg.toIata)") { open(leg) }
            }
        }
    }

    private func read(_ payload: String) {
        guard !done, !choosing else { return }
        let found = parseBoardingPass(payload, today: shell.clock.today(tz: TimeZone.current.identifier))
        if found.isEmpty {
            notice = String(localized: "That barcode is not a boarding pass.")
        } else if found.count == 1 {
            open(found[0])
        } else {
            legs = found
            choosing = true
        }
    }

    private func readPhoto(_ item: PhotosPickerItem) async {
        photo = nil
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            notice = String(localized: "That photo could not be opened.")
            return
        }
        var request = DetectBarcodesRequest()
        request.symbologies = [.pdf417, .aztec, .qr]
        let payloads = ((try? await request.perform(on: data)) ?? []).compactMap(\.payloadString)
        guard !payloads.isEmpty else {
            notice = String(localized: "No barcode was found in that photo.")
            return
        }
        let today = shell.clock.today(tz: TimeZone.current.identifier)
        read(payloads.first { !parseBoardingPass($0, today: today).isEmpty } ?? payloads[0])
    }

    /// Replaces this screen with Add flight, so Back does not reopen the camera.
    private func open(_ leg: BoardingPassLeg) {
        guard !done else { return }
        done = true
        // `scan` is the boarding-pass barcode source (P1 contract section 5, migration 20261126000010).
        let prefill = AddFlightPrefill(flight: leg.flight.display, date: leg.date?.iso, pnr: leg.pnr, source: .scan, seat: leg.seat)
        if !shell.path.isEmpty { shell.path.removeLast() }
        shell.path.append(Route.add(prefill))
    }
}

/// VisionKit's live barcode scanner for PDF417, Aztec and QR, the three symbologies boarding passes use.
private struct BarcodeScanner: UIViewControllerRepresentable {
    var onRead: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.pdf417, .aztec, .qr])],
            qualityLevel: .accurate,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.onRead = onRead
        if !scanner.isScanning { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onRead: onRead) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onRead: (String) -> Void

        init(onRead: @escaping (String) -> Void) { self.onRead = onRead }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for case .barcode(let barcode) in addedItems {
                if let payload = barcode.payloadStringValue { onRead(payload) }
            }
        }
    }
}
