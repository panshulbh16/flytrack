import Foundation

public struct GmailMessageContent: Equatable, Sendable {
    public var subject: String
    /// The text/plain body, a line break, then the text/html body as plain text; either alone when only one
    /// decodes. Empty when neither does.
    public var text: String
    public var pdfAttachmentId: String?
    /// Bytes, as Gmail reports it. 0 without a PDF.
    public var pdfSize: Int64
}

extension GmailMessage {
    /// Picks the readable body and the first PDF attachment out of a `format=full` message.
    public func content() -> GmailMessageContent {
        guard let payload else { return GmailMessageContent(subject: "", text: "", pdfAttachmentId: nil, pdfSize: 0) }
        let parts = payload.flattened()
        let subject = payload.headers.first { $0.name.caseInsensitiveCompare("Subject") == .orderedSame }?.value ?? ""
        // Both parts: a plain part can be only "View in browser" while the ticket is in the HTML.
        // ponytail: no dedupe. A leg in both can parse twice; fromTicket keeps the first.
        let text = [parts.firstBody("text/plain"), parts.firstBody("text/html").map(htmlToText)]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
        let pdf = parts.first { $0.isPDF && $0.body?.attachmentId != nil }
        return GmailMessageContent(subject: subject, text: text, pdfAttachmentId: pdf?.body?.attachmentId, pdfSize: pdf?.body?.size ?? 0)
    }
}

private extension GmailPart {
    /// This part and every nested part, depth first.
    func flattened() -> [GmailPart] { [self] + parts.flatMap { $0.flattened() } }

    /// A named part is an attachment, never the email body.
    var isAttachment: Bool { !(filename ?? "").isEmpty }

    var isPDF: Bool {
        mimeType?.lowercased() == "application/pdf" || (filename ?? "").lowercased().hasSuffix(".pdf")
    }

    // ponytail: assumes UTF-8 (what airline mail sends); other charsets decode lossily.
    func decodedText() -> String? {
        body?.data.flatMap(decodeBase64URL).map { String(decoding: $0, as: UTF8.self) }
    }
}

private extension Array where Element == GmailPart {
    func firstBody(_ mimeType: String) -> String? {
        lazy.filter { $0.mimeType?.lowercased() == mimeType && !$0.isAttachment }
            .compactMap { $0.decodedText() }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}

/// Gmail's base64url, padded or not. Nil when it is not base64.
public func decodeBase64URL(_ s: String) -> Data? {
    var text = s.filter { !$0.isWhitespace && $0 != "=" }
        .replacingOccurrences(of: "-", with: "+")
        .replacingOccurrences(of: "_", with: "/")
    text += String(repeating: "=", count: (4 - text.count % 4) % 4)
    return Data(base64Encoded: text)
}

/// Booking-email HTML as plain lines: comments, scripts and styles dropped, block ends become line
/// breaks, other tags become spaces, entities decoded. Regex-based so it runs on Linux.
public func htmlToText(_ html: String) -> String {
    var text = html
    text = replaceAll(#"(?is)<!--.*?-->"#, in: text, with: " ")
    text = replaceAll(#"(?is)<(script|style)\b[^>]*>.*?</\1\s*>"#, in: text, with: " ")
    text = replaceAll(#"(?i)<br\s*/?>|</(p|div|tr|li|h[1-6]|table|ul|ol)\s*>"#, in: text, with: "\n")
    text = replaceAll(#"(?s)<[^>]*>"#, in: text, with: " ")
    text = decodeEntities(text)
    return text.components(separatedBy: "\n")
        .map { replaceAll("[ \t\r\u{000C}\u{00A0}]+", in: $0, with: " ").trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
        .joined(separator: "\n")
}

private func replaceAll(_ pattern: String, in text: String, with template: String) -> String {
    let ns = text as NSString
    return RE.regex(pattern).stringByReplacingMatches(in: text, options: [], range: NSRange(location: 0, length: ns.length), withTemplate: template)
}

private let namedEntities: [String: String] = [
    "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
    "ndash": "–", "mdash": "—", "rarr": "→",
]

/// One pass, so "&amp;lt;" becomes "&lt;" and not "<". Unknown entities stay as written.
private func decodeEntities(_ text: String) -> String {
    let ns = text as NSString
    var out = ""
    var cursor = 0
    for (_, match) in RE.matches(#"&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]{2,8});"#, in: text) {
        out += ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
        out += entity(RE.group(ns, match, 1)) ?? ns.substring(with: match.range)
        cursor = match.range.location + match.range.length
    }
    return out + ns.substring(from: cursor)
}

private func entity(_ name: String) -> String? {
    let scalar: UInt32?
    if name.hasPrefix("#x") || name.hasPrefix("#X") {
        scalar = UInt32(name.dropFirst(2), radix: 16)
    } else if name.hasPrefix("#") {
        scalar = UInt32(name.dropFirst())
    } else {
        return namedEntities[name.lowercased()]
    }
    return scalar.flatMap { Unicode.Scalar($0) }.map { String(Character($0)) }
}
