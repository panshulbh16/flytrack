package app.flytrack.feature.importticket

import app.flytrack.core.flightnumber.FlightNumber
import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.normalizePnr
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One flight read off a ticket. [date] is the departure date in the airport's calendar, when found. */
data class ParsedFlight(
    val flight: FlightNumber,
    val date: LocalDate?,
    val fromIata: String?,
    val toIata: String?,
)

/** What the on-device parser found. [pnr] is a 6-character booking reference, or null. */
data class ParsedTicket(
    val flights: List<ParsedFlight>,
    val pnr: String?,
)

/** Carriers a ticket from an Indian airline is most likely to mention. Shown first when several match. */
val INDIAN_CARRIERS = setOf("6E", "AI", "IX", "QP", "SG", "UK", "G8", "I5", "9I")

/** Two-letter words that sit in front of a date ("on 25 Nov") and are not airline codes. */
private val NOT_A_CARRIER = setOf(
    "ON", "TO", "AT", "IN", "OF", "NO", "OR", "AN", "BY", "IS", "AM", "PM", "IF", "WE", "MY",
    "DO", "GO", "UP", "SO", "AS", "BE", "IT", "US", "THE",
)

private val MONTHS = mapOf(
    "JAN" to 1, "JANUARY" to 1,
    "FEB" to 2, "FEBRUARY" to 2,
    "MAR" to 3, "MARCH" to 3,
    "APR" to 4, "APRIL" to 4,
    "MAY" to 5,
    "JUN" to 6, "JUNE" to 6,
    "JUL" to 7, "JULY" to 7,
    "AUG" to 8, "AUGUST" to 8,
    "SEP" to 9, "SEPT" to 9, "SEPTEMBER" to 9,
    "OCT" to 10, "OCTOBER" to 10,
    "NOV" to 11, "NOVEMBER" to 11,
    "DEC" to 12, "DECEMBER" to 12,
)

/** Three-letter tokens that are not airports. */
private val NOT_IATA = MONTHS.keys + setOf(
    "THE", "AND", "FOR", "NOT", "YOU", "ARE", "WAS", "HAS", "HAD", "HIS", "HER", "OUR", "OUT", "OFF",
    "ALL", "ANY", "NEW", "ONE", "TWO", "GST", "INR", "USD", "PDF", "SMS", "OTP", "PNR", "REF", "VIA",
    "NON", "YES", "DAY", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "TAX", "FEE", "BAG", "WEB",
    "APP", "ROW", "ETA", "ETD", "STD", "STA", "INR", "MR", "MRS", "MS",
)

/** A flight number whose digits are already digits. Airport codes like DEL must not match. */
private val FLIGHT_TOKEN = Regex("""(?<![A-Z0-9])([A-Z0-9]{2})[ \t]*-?[ \t]*([0-9]{1,4})([A-Z])?(?![A-Z0-9])""")

/** Same shape, but the number may contain OCR lookalikes (I, L, O) next to a real digit: "234l". */
private val OCR_FLIGHT = Regex("""(?<![A-Z0-9])([A-Z0-9]{2})([ \t]*)-?([ \t]*)([0-9ILO]*[0-9][0-9ILO]*)([A-Z])?(?![A-Z0-9])""")
// A bare number after the airline name. Skip it when a real designator ("6E 2341") already follows,
// otherwise "IndiGo\n6E 2341" is also read as flight 6E with number 6.
private const val NOT_A_DESIGNATOR = """(?!\s+(?!\d\d)[A-Z0-9]{2}\s*-?\s*\d)"""
private val AIRLINE_NUMBER = listOf(
    Regex("""\bAIR\s+INDIA\s+EXPRESS(?:\s+FLIGHT)?$NOT_A_DESIGNATOR\s+(\d{1,4})([A-Z])?\b""") to "IX",
    Regex("""\bAIR\s+INDIA(?:\s+FLIGHT)?$NOT_A_DESIGNATOR\s+(\d{1,4})([A-Z])?\b""") to "AI",
    Regex("""\bINDIGO(?:\s+FLIGHT)?$NOT_A_DESIGNATOR\s+(\d{1,4})([A-Z])?\b""") to "6E",
    Regex("""\bAKASA(?:\s+AIR)?(?:\s+FLIGHT)?$NOT_A_DESIGNATOR\s+(\d{1,4})([A-Z])?\b""") to "QP",
    Regex("""\bSPICE\s*JET(?:\s+FLIGHT)?$NOT_A_DESIGNATOR\s+(\d{1,4})([A-Z])?\b""") to "SG",
)
private val DATE_WITH_YEAR = Regex("""\b(\d{1,2})\s*([A-Z]{3,9})\s*(\d{2,4})\b""")
private val DATE_WEEKDAY = Regex("""\b(?:MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*,?\s+(\d{1,2})\s+([A-Z]{3,9})\b""")
private val DATE_DAY_MONTH = Regex("""\b(\d{1,2})\s+([A-Z]{3,9})\b""")
private val DATE_SLASH = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b""")
private val PNR_LABEL = Regex(
    """\b(?:PNR|BOOKING\s+(?:REFERENCE|REF|ID|CODE)|CONFIRMATION(?:\s+(?:CODE|NUMBER))?|RECORD\s+LOCATOR)\b(?:\s*(?:NO\.?|NUMBER|CODE))?\s*[:#\-]*\s*([A-Z0-9]{6})\b""",
)
private val IATA_PAIR = Regex("""(?<![A-Z])([A-Z]{3})\s*(?:→|>|-|/|TO)\s*([A-Z]{3})(?![A-Z])""")
private val IATA_PAREN = Regex("""\(([A-Z]{3})\)""")

private data class Hit<T>(val start: Int, val end: Int, val value: T)

/**
 * Reads flight numbers, a booking reference and dates out of ticket text (an e-mail, an SMS, or
 * the text ML Kit read from a PDF or screenshot). [today] is the phone's date, used only when a
 * date has no year.
 */
fun parseTicketText(text: String, today: LocalDate): ParsedTicket {
    val normalized = normalizeTicketText(text)
    val flights = findFlights(normalized)
    val dates = findDates(normalized, today)
    val paired = pairFlights(normalized, flights, dates)
        .sortedWith(compareBy({ it.flight.carrier !in INDIAN_CARRIERS }, { it.order }))
        .map { ParsedFlight(it.flight, it.date, it.fromIata, it.toIata) }
        .distinctBy { Triple(it.flight, it.date, it.fromIata) }
    return ParsedTicket(flights = paired, pnr = findPnr(normalized))
}

/** Upper-cases ticket text and repairs the OCR mistakes the fixtures and real scans share. */
fun normalizeTicketText(raw: String): String {
    var text = raw
        .replace('\u00A0', ' ')
        .replace('\u200B', ' ')
        .replace('–', '-')
        .replace('—', '-')
        .replace('→', '>')
    text = text.replace(Regex("(?i)lndigo"), "INDIGO")
    text = text.replace(Regex("(?i)1ndigo"), "INDIGO")
    text = text.uppercase()
    text = text.replace(Regex("""\bN0V\b"""), "NOV")
        .replace(Regex("""\b0CT\b"""), "OCT")
        .replace(Regex("""\bDE0\b"""), "DEC")
    // Digit lookalikes only inside the numeric part of something shaped like a flight number.
    text = OCR_FLIGHT.replace(text) { match ->
        val digits = match.groupValues[4].map { ch ->
            when (ch) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> ch
            }
        }.joinToString("")
        match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + digits + match.groupValues[5]
    }
    return text
}

private data class FlightHit(val start: Int, val end: Int, val flight: FlightNumber)

private fun findFlights(text: String): List<FlightHit> {
    val found = mutableListOf<FlightHit>()
    for (match in FLIGHT_TOKEN.findAll(text)) {
        val candidate = match.groupValues[1] + " " + match.groupValues[2].trimStart('0').ifEmpty { "0" } + match.groupValues[3]
        val parsed = parseFlightNumber(candidate) ?: continue
        if (parsed.carrier in NOT_A_CARRIER) continue
        found += FlightHit(match.range.first, match.range.last + 1, parsed)
    }
    for ((regex, carrier) in AIRLINE_NUMBER) {
        for (match in regex.findAll(text)) {
            val number = match.groupValues[1].trimStart('0').ifEmpty { "0" } + match.groupValues[2]
            val parsed = parseFlightNumber("$carrier $number") ?: continue
            val near = found.any { it.flight == parsed && kotlin.math.abs(it.start - match.range.first) < 40 }
            if (near) continue
            found += FlightHit(match.range.first, match.range.last + 1, parsed)
        }
    }
    return found.distinctBy { it.start to it.flight }.sortedBy { it.start }.take(8)
}

private fun findDates(text: String, today: LocalDate): List<Hit<LocalDate>> {
    val hits = mutableListOf<Hit<LocalDate>>()
    fun add(match: MatchResult, date: LocalDate?) {
        if (date != null) hits += Hit(match.range.first, match.range.last + 1, date)
    }
    for (match in DATE_WITH_YEAR.findAll(text)) {
        add(match, dateFromParts(match.groupValues[1], match.groupValues[2], match.groupValues[3], today))
    }
    for (match in DATE_SLASH.findAll(text)) {
        val day = match.groupValues[1].toIntOrNull()
        val month = match.groupValues[2].toIntOrNull()
        val year = expandYear(match.groupValues[3])
        if (day != null && month != null && year != null) add(match, localDateOrNull(year, month, day))
    }
    for (match in DATE_WEEKDAY.findAll(text)) {
        add(match, dateFromParts(match.groupValues[1], match.groupValues[2], yearRaw = null, today))
    }
    for (match in DATE_DAY_MONTH.findAll(text)) {
        add(match, dateFromParts(match.groupValues[1], match.groupValues[2], yearRaw = null, today))
    }
    return dropOverlaps(hits)
}

private fun <T> dropOverlaps(hits: List<Hit<T>>): List<Hit<T>> {
    val sorted = hits.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
    val kept = mutableListOf<Hit<T>>()
    for (hit in sorted) {
        if (kept.any { hit.start < it.end && it.start < hit.end }) continue
        kept += hit
    }
    return kept.sortedBy { it.start }
}

private fun dateFromParts(dayRaw: String, monthRaw: String, yearRaw: String?, today: LocalDate): LocalDate? {
    val day = dayRaw.toIntOrNull() ?: return null
    val month = MONTHS[monthRaw] ?: return null
    val year = yearRaw?.let { expandYear(it) }
    return if (year != null) localDateOrNull(year, month, day) else inferYear(today, month, day)
}

/** Two-digit years are 20xx. A missing year stays in this year when it is bookable, else next year. */
internal fun inferYear(today: LocalDate, month: Int, day: Int): LocalDate? {
    val earliest = today.minus(DatePeriod(days = 2))
    val latest = today.plus(DatePeriod(days = 365))
    val thisYear = localDateOrNull(today.year, month, day)
    if (thisYear != null && thisYear in earliest..latest) return thisYear
    val next = localDateOrNull(today.year + 1, month, day)
    if (next != null && next in earliest..latest) return next
    return thisYear
}

private fun expandYear(raw: String): Int? {
    val year = raw.toIntOrNull() ?: return null
    return when (raw.length) {
        4 -> year.takeIf { it in 2000..2099 }
        2 -> 2000 + year
        else -> null
    }
}

private fun localDateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    try {
        LocalDate(year, month, day)
    } catch (_: IllegalArgumentException) {
        null
    }

private data class Paired(
    val order: Int,
    val flight: FlightNumber,
    val date: LocalDate?,
    val fromIata: String?,
    val toIata: String?,
)

/** A date or a route belongs to the flight written before it (or the first flight, if it comes first). */
private fun ownerIndex(position: Int, flights: List<FlightHit>): Int {
    val preceding = flights.indexOfLast { it.end <= position }
    return if (preceding >= 0) preceding else 0
}

private fun pairFlights(text: String, flights: List<FlightHit>, dates: List<Hit<LocalDate>>): List<Paired> {
    val datesByFlight = dates.groupBy { ownerIndex(it.start, flights) }
    val routes = routeHits(text)
    val routesByFlight = routes.groupBy { ownerIndex(it.start, flights) }
    return flights.mapIndexed { index, flight ->
        val date = datesByFlight[index].orEmpty().minByOrNull { kotlin.math.abs(it.start - flight.start) }?.value
        val route = routesByFlight[index].orEmpty().minByOrNull { kotlin.math.abs(it.start - flight.start) }?.value
        Paired(flight.start, flight.flight, date, route?.first, route?.second)
    }
}

private fun routeHits(text: String): List<Hit<Pair<String, String>>> {
    val hits = mutableListOf<Hit<Pair<String, String>>>()
    for (match in IATA_PAIR.findAll(text)) {
        val from = iataOrNull(match.groupValues[1])
        val to = iataOrNull(match.groupValues[2])
        if (from != null && to != null && from != to) hits += Hit(match.range.first, match.range.last + 1, from to to)
    }
    // "(DEL) ... (BOM)" when there is no DEL→BOM on the same line.
    var cursor = 0
    while (cursor < text.length) {
        val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        val line = text.substring(cursor, lineEnd)
        if (IATA_PAIR.containsMatchIn(line).not()) {
            val paren = IATA_PAREN.findAll(line).map { iataOrNull(it.groupValues[1]) }.filterNotNull().distinct().take(2).toList()
            if (paren.size == 2) hits += Hit(cursor, lineEnd, paren[0] to paren[1])
        }
        cursor = lineEnd + 1
    }
    return hits
}

private fun iataOrNull(code: String): String? = code.takeIf { it.length == 3 && it !in NOT_IATA }

private fun findPnr(text: String): String? {
    for (match in PNR_LABEL.findAll(text)) {
        val token = match.groupValues[1]
        if (parseFlightNumber(token) != null) continue
        val pnr = normalizePnr(token)
        if (pnr != null && pnr.length == 6) return pnr
    }
    return null
}

/** One leg of a boarding-pass barcode. */
data class BoardingPassLeg(
    val pnr: String?,
    val fromIata: String,
    val toIata: String,
    val flight: FlightNumber,
    val date: LocalDate?,
    val seat: String?,
)

/**
 * Reads the mandatory fields of each leg of an IATA BCBP barcode (Resolution 792, format "M"):
 * "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100". Empty when the text is not one.
 * The barcode carries a day of the year but no year: the leg goes to the year that puts it nearest [today].
 */
fun parseBoardingPass(raw: String, today: LocalDate): List<BoardingPassLeg> {
    val legCount = raw.getOrNull(1)?.digitToIntOrNull() ?: return emptyList()
    if (raw[0] != 'M' || legCount == 0) return emptyList()
    val legs = mutableListOf<BoardingPassLeg>()
    var start = 23 // "M", leg count, 20-character name, e-ticket flag
    repeat(legCount) {
        if (raw.length < start + 37) return legs
        fun field(offset: Int, length: Int) = raw.substring(start + offset, start + offset + length).trim()
        val from = field(7, 3)
        val to = field(10, 3)
        val flight = parseFlightNumber(field(13, 3) + " " + field(16, 5))
        if (!isIataCode(from) || !isIataCode(to) || flight == null) return legs
        legs += BoardingPassLeg(
            pnr = normalizePnr(field(0, 7)),
            fromIata = from,
            toIata = to,
            flight = flight,
            date = field(21, 3).toIntOrNull()?.let { julianDate(it, today) },
            seat = field(25, 4).trimStart('0').takeIf { BOARDING_SEAT.matches(it) },
        )
        val conditionalSize = field(35, 2).toIntOrNull(16) ?: return legs
        start += 37 + conditionalSize
    }
    return legs
}

/** "12A" once the leading zeros of "012A" are gone. "INF ", "GATE" and blanks are not seats. */
private val BOARDING_SEAT = Regex("""[0-9]{1,3}[A-Z]""")

private fun isIataCode(code: String) = code.length == 3 && code.all { it in 'A'..'Z' }

internal fun julianDate(dayOfYear: Int, near: LocalDate): LocalDate? {
    if (dayOfYear !in 1..366) return null
    return listOf(near.year - 1, near.year, near.year + 1)
        .mapNotNull { year ->
            LocalDate(year, 1, 1).plus(DatePeriod(days = dayOfYear - 1)).takeIf { it.year == year } // day 366 of a non-leap year
        }
        .minByOrNull { kotlin.math.abs(it.toEpochDays() - near.toEpochDays()) }
}
