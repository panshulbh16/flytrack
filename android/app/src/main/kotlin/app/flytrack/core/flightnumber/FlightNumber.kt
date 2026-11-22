package app.flytrack.core.flightnumber

/** A parsed flight designator: [carrier] is the 2-character IATA airline code, [number] has no leading zeros. */
data class FlightNumber(val carrier: String, val number: String) {
    /** "6E 2341": the form the backend accepts and the UI shows. */
    val display: String get() = "$carrier $number"
}

// JavaScript's \s, spelled out: Java's \s is ASCII-only, so pasted text with a no-break space
// ("6E 2341", common in airline e-mails) would be accepted by the server but rejected here.
private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

private val FLIGHT_NUMBER = Regex("^$WS*([A-Za-z0-9]{2})$WS*-?$WS*([0-9]{1,4})([A-Za-z]?)$WS*$")
private val TWO_DIGITS = Regex("^[0-9]{2}$")

/**
 * Kotlin port of `parseFlightNumber` in supabase/functions/_shared/engine.ts. It must accept and
 * normalise exactly what the server does, so the app never rejects input the server would take:
 * "6E 2341", "6e2341", "AI-101", "6E 0234" -> 234, "AI 101A" -> 101A. Two-digit carriers are rejected.
 */
fun parseFlightNumber(input: String): FlightNumber? {
    val match = FLIGHT_NUMBER.find(input) ?: return null
    val (rawCarrier, digits, suffix) = match.destructured
    val carrier = rawCarrier.uppercase()
    if (TWO_DIGITS.matches(carrier)) return null // IATA airline codes are never two digits
    return FlightNumber(carrier, "${digits.toInt()}${suffix.uppercase()}")
}
