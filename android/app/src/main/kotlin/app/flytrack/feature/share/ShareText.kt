package app.flytrack.feature.share

import java.util.Locale

/** What the share message names: "6E 2341 DEL→BOM". */
data class ShareFlight(
    val carrierIata: String,
    val flightNumber: String,
    val depIata: String,
    val arrIata: String,
)

/** Default of `SHARE_BASE_URL` (P1 contract, section 2). */
const val DEFAULT_SHARE_BASE_URL = "https://heygate.app/f/"

/**
 * The public link `{SHARE_BASE_URL}#<token>`. The token sits in the fragment so it never reaches
 * the web server's logs. A blank base falls back to [DEFAULT_SHARE_BASE_URL]; a fragment already in
 * the base is replaced.
 */
fun shareUrl(baseUrl: String, token: String): String {
    val base = baseUrl.trim().ifEmpty { DEFAULT_SHARE_BASE_URL }.substringBefore('#')
    return "$base#${token.trim()}"
}

/**
 * The message sent to WhatsApp or the share sheet: the Hinglish line, then the English line with
 * the link. Both templates take `%1$s` carrier, `%2$s` number, `%3$s` departure IATA, `%4$s`
 * arrival IATA and `%5$s` the url (strings_share.xml).
 */
fun shareMessage(hinglishTemplate: String, englishTemplate: String, flight: ShareFlight, url: String): String {
    val args = arrayOf(flight.carrierIata, flight.flightNumber, flight.depIata, flight.arrIata, url)
    val hinglish = String.format(Locale.ROOT, hinglishTemplate, *args).trim()
    val english = String.format(Locale.ROOT, englishTemplate, *args).trim()
    return listOf(hinglish, english).filter { it.isNotEmpty() }.joinToString("\n")
}

/** A link is ready: the screen builds the localised text and hands it to [target]. */
data class ShareRequest(val flight: ShareFlight, val url: String, val target: ShareTarget)
