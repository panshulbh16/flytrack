import UIKit
import UniformTypeIdentifiers

/// Copies a shared PDF, image or text into the app group, then opens HeyGate.
/// The share extension cannot read the main app's database, so the host app picks the files up from the group.
final class ShareViewController: UIViewController {
    private let group = "group.app.heygate.flytrack"

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await receive() }
    }

    private func receive() async {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            finish()
            return
        }
        var files: [String] = []
        var text: String?
        var mime: String?
        for item in extensionItems {
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
                    mime = "application/pdf"
                    if let url = await loadFile(provider, type: UTType.pdf.identifier) { files.append(url) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    mime = mime ?? "image/*"
                    if let url = await loadFile(provider, type: UTType.image.identifier) { files.append(url) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    mime = mime ?? "text/plain"
                    text = await loadText(provider)
                }
            }
        }
        writeManifest(files: files, text: text, mime: mime)
        if let url = URL(string: "heygate://import") {
            extensionContext?.open(url) { _ in self.finish() }
        } else {
            finish()
        }
    }

    private func loadFile(_ provider: NSItemProvider, type: String) async -> String? {
        let appGroup = group
        return await withCheckedContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url, let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
                    continuation.resume(returning: nil)
                    return
                }
                let destination = container.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
                try? FileManager.default.copyItem(at: url, to: destination)
                continuation.resume(returning: destination.absoluteString)
            }
        }
    }

    private func loadText(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                continuation.resume(returning: item as? String)
            }
        }
    }

    private func writeManifest(files: [String], text: String?, mime: String?) {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return }
        var object: [String: Any] = ["files": files]
        if let text { object["text"] = text }
        if let mime { object["mime"] = mime }
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        try? data.write(to: container.appendingPathComponent("pending-import.json"))
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}
