package app.flytrack.feature.gmailimport

import java.util.Base64

/**
 * What the scanner reads from one message: the Subject header, the body as plain text, and the first
 * PDF attachment (id and size, for `messages.attachments.get`). [pdfSize] is 0 when there is no PDF.
 */
data class MessageContent(val subject: String, val text: String, val pdfAttachmentId: String?, val pdfSize: Long)

/**
 * The body is the first non-empty `text/plain` part, a line break, then the first `text/html` part as
 * text; either alone when only one exists. Both, because a plain part can be just "View in browser"
 * while the ticket is only in the HTML. A part that cannot be decoded reads as missing, so a broken
 * message parses to nothing and is skipped.
 */
fun GmailMessage.content(): MessageContent {
    val root = payload ?: return MessageContent(subject = "", text = "", pdfAttachmentId = null, pdfSize = 0)
    val parts = root.flatten()
    val subject = root.headers.firstOrNull { it.name.equals("Subject", ignoreCase = true) }?.value.orEmpty()
    val plain = parts.firstNotNullOfOrNull { it.inlineText("text/plain") }
    val html = parts.firstNotNullOfOrNull { it.inlineText("text/html") }?.let(::htmlToText)
    // ponytail: no dedupe of the two parts. A leg in both can parse twice; GmailSuggestions.fromTicket keeps the first.
    val text = listOfNotNull(plain, html).filter { it.isNotEmpty() }.joinToString("\n")
    val pdf = parts.firstOrNull { it.isPdf() && it.body?.attachmentId != null }
    return MessageContent(subject, text, pdf?.body?.attachmentId, pdf?.body?.size ?: 0)
}

/** This part and every part below it, depth first: the order Gmail lists them in. */
private fun GmailPart.flatten(): List<GmailPart> = listOf(this) + parts.flatMap { it.flatten() }

/** The decoded body of an inline (not attached) [type] part, or null when there is none or it is blank. */
private fun GmailPart.inlineText(type: String): String? {
    if (!mimeType.equals(type, ignoreCase = true) || !filename.isNullOrEmpty()) return null
    val data = body?.data ?: return null
    // ponytail: assumes UTF-8, which Gmail booking mail almost always is; read the part's charset if a sender needs it.
    return runCatching { decodeBase64Url(data).decodeToString() }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun GmailPart.isPdf(): Boolean =
    mimeType.equals("application/pdf", ignoreCase = true) || filename.orEmpty().endsWith(".pdf", ignoreCase = true)

/** Gmail bodies and attachments are base64url, padded or not. Throws IllegalArgumentException when [data] is not base64. */
fun decodeBase64Url(data: String): ByteArray =
    Base64.getUrlDecoder().decode(
        data.filterNot { it.isWhitespace() || it == '=' }.replace('+', '-').replace('/', '_'),
    )

private val HTML_DROP = Regex("""(?is)<(script|style|head)\b.*?</\1\s*>|<!--.*?-->""")
private val WHITESPACE = Regex("""\s+""")
private val HTML_LINE_END = Regex("""(?i)<br\s*/?>|</(p|div|tr|li|table|h[1-6])\s*>""")
private val HTML_TAG = Regex("""<[^>]*>""")
private val HTML_NUMERIC = Regex("""&#([xX][0-9a-fA-F]+|[0-9]+);""")
private val HTML_NAMED = listOf(
    "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
    "&rarr;" to "→", "&ndash;" to "–", "&mdash;" to "—",
)
private val SPACES = Regex("""[ \t ]+""")

/**
 * HTML mail as the text a reader sees: scripts, styles and comments dropped, one line per
 * paragraph, row or break, table cells and other tags as spaces, common entities decoded.
 */
fun htmlToText(html: String): String {
    var text = html.replace(HTML_DROP, " ")
        .replace(WHITESPACE, " ")
        .replace(HTML_LINE_END, "\n")
        .replace(HTML_TAG, " ")
        .replace(HTML_NUMERIC) { match ->
            val raw = match.groupValues[1]
            val code = if (raw[0] == 'x' || raw[0] == 'X') raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
        }
    for ((entity, value) in HTML_NAMED) text = text.replace(entity, value, ignoreCase = true)
    text = text.replace("&amp;", "&", ignoreCase = true) // last, so "&amp;lt;" stays "&lt;"
    return text.lines()
        .map { it.replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
