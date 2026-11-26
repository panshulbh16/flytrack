package app.flytrack.feature.calendarimport

import app.flytrack.feature.airports.AirportList
import app.flytrack.feature.airports.CatalogAirport
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

// Kotlin port of HeyTurbulence src/calendar-parser.js (parseFlightEvent, splitLegs) for the P3 calendar
// import (docs/P3_CONTRACTS.md A3). The iOS port runs the same fixtures:
// android/app/src/test/resources/fixtures/calendar_events.json, whose expected values come from the
// JavaScript parser itself. Pure Kotlin, no android.* types.
//
// Differences from the JavaScript, all on purpose:
//  * review notes are typed [ReviewIssue]s (the apps word them in their own string resources);
//  * no redacted `report` and no display strings (title, date label): the screen formats those;
//  * the source id prefix is a parameter (the device calendar is not Google's "primary" calendar).

/** One time of an event, in Google Calendar's shape. The Android reader fills [dateTime] with a UTC instant. */
data class EventTime(
    /** "2026-11-26T06:00:00+05:30", "2026-11-26T00:30:00Z", or a local "2026-11-26T06:00:00" with [timeZone]. */
    val dateTime: String? = null,
    /** IANA zone of an offset-free [dateTime]. */
    val timeZone: String? = null,
    /** "2026-11-26" for all-day events. */
    val date: String? = null,
)

/** A calendar event as the parser sees it (Google Calendar's field names). */
data class CalendarEvent(
    val id: String,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: EventTime? = null,
    val end: EventTime? = null,
    /** "cancelled" drops the event. */
    val status: String? = null,
    /** Google's eventType; birthday, workingLocation, outOfOffice and focusTime are never flights. */
    val eventType: String? = null,
)

/** Why a candidate needs a look before it is added (the JavaScript's review notes). */
enum class ReviewIssue {
    NO_FROM,
    NO_TO,
    SAME_AIRPORT,

    /** All-day, or times without an offset or a valid zone. */
    UNTIMED,

    /** The event names different routes. */
    CONFLICT,

    /** The event covers several legs; see [CalendarCandidate.legs] / [splitLegs]. */
    MULTI_LEG,

    /** A split leg whose own line states two clock times (shown for review, never filled in). */
    STATED_TIMES,
}

/** Which times a split leg still lacks. */
enum class MissingTimes { TAKEOFF, LANDING, BOTH }

/** What one leg's own line says: a flight number (when exactly one) and up to two clock times. */
data class LegDetail(val number: String, val times: List<String>)

/** A flight-like event (or, after [splitLegs], one leg of it). */
data class CalendarCandidate(
    val sourceId: String,
    val from: CatalogAirport?,
    val to: CatalogAirport?,
    /** IATA pairs of a multi-leg event, empty otherwise. */
    val legs: List<Pair<String, String>>,
    val legDetails: List<LegDetail>,
    val startInstant: Instant?,
    val endInstant: Instant?,
    /** "2026-11-26T06:00" at the departure airport, or "" when unknown. */
    val departLocal: String,
    /** "2026-11-26T13:00" at the arrival airport, or "" when unknown. */
    val arriveLocal: String,
    /** "SQ 509" (as written: leading zeros are kept), or "". */
    val number: String,
    val needsReview: Boolean,
    val requiresCorrection: Boolean,
    val issues: List<ReviewIssue>,
    /** The start instant (ISO), the all-day date, or "". */
    val sortDate: String,
    /** The event's all-day start date ("2026-11-26"), else null. */
    val allDayDate: String?,
    /** 1-based leg index after [splitLegs]; null for a whole event. */
    val leg: Int? = null,
    val legCount: Int? = null,
    val missing: MissingTimes? = null,
    /** Clock times the leg's own line states ([ReviewIssue.STATED_TIMES]). */
    val statedTimes: List<String> = emptyList(),
)

/** Parses calendar events against the bundled airport catalog. Thread-safe after construction. */
class CalendarFlightParser(private val catalog: AirportList, private val sourcePrefix: String = DEVICE_SOURCE_PREFIX) {

    private class Terms(val airport: CatalogAirport, val aliases: List<String>, val name: String?, val city: String?)

    private val terms: List<Terms> by lazy {
        catalog.airports.map { a ->
            Terms(
                airport = a,
                aliases = ALIASES[a.iata].orEmpty().map(::normalize),
                name = if (a.name.length >= 10) plain(a.name) else null,
                city = if (a.city.length >= 4 && normalize(a.city) !in AMBIGUOUS) normalize(a.city) else null,
            )
        }
    }

    private fun byCode(code: String): CatalogAirport? = catalog[code]

    private fun resolve(segment: String): CatalogAirport? {
        val codes = THREE_CAPS.findAll(segment).map { it.value }.filter { byCode(it) != null }.distinct().toList()
        if (codes.size == 1) return byCode(codes[0])
        if (codes.size > 1) return null
        val text = normalize(segment)
        val specific = terms.filter { t -> t.aliases.any { boundary(text, it) } }.map { it.airport }
        if (specific.size == 1) return specific[0]
        if (specific.size > 1) return null
        val plainText = plain(text)
        val named = terms.filter { t -> t.name != null && boundary(plainText, t.name) }.map { it.airport }
        if (named.size == 1) return named[0]
        if (named.size > 1) return null
        val matches = terms.filter { t -> t.city != null && boundary(text, t.city) }.map { it.airport }
        return if (matches.size == 1) matches[0] else null
    }

    /** Sequences of known codes such as "BLR → DXB → LHR" or "BLR-DEL-BLR". */
    private fun chains(text: String): List<List<CatalogAirport>> {
        val found = mutableListOf<List<CatalogAirport>>()
        for (match in CHAIN.findAll(text)) {
            val codes = match.value.split(NON_CAPS).filter { it.isNotEmpty() }
            if (codes.size >= 2 && codes.all { byCode(it) != null }) found += codes.map { byCode(it)!! }
        }
        return found
    }

    private class Route(val from: CatalogAirport?, val to: CatalogAirport?)

    private fun routes(text: String): List<Route> {
        val found = mutableListOf<Route>()
        for (chain in chains(text)) {
            for (i in 0 until chain.size - 1) found += Route(chain[i], chain[i + 1])
        }
        val departure = DEPARTURE_LABEL.find(text)
        val arrival = ARRIVAL_LABEL.find(text)
        if (departure != null || arrival != null) {
            found += Route(departure?.let { resolve(it.groupValues[1]) }, arrival?.let { resolve(it.groupValues[1]) })
        }
        var budget = MAX_ROUTE_LINES
        for (line in text.split(LINE_SPLIT)) {
            if (line.length > MAX_LINE) continue
            if (budget <= 0) break
            val route = ROUTE_LINE_1.find(line) ?: ROUTE_LINE_2.find(line) ?: ROUTE_LINE_3.find(line) ?: continue
            budget--
            val first = route.groupValues[1]
            if (TO_PREFIX.containsMatchIn(first.trim())) {
                found += Route(null, resolve(first.replaceFirst(TO_PREFIX_REPLACE, "")))
            } else {
                found += Route(resolve(first), resolve(route.groupValues[2]))
            }
        }
        return found
    }

    private fun flightNumber(summary: String, description: String): String {
        val texts = listOf(summary) + description.split('\n').filterNot { SECRET_LINE.containsMatchIn(it) }
        for (text in texts) {
            val m = FLIGHT_NUMBER.find(text)
            if (m != null) return "${m.groupValues[1]} ${m.groupValues[2]}"
        }
        return ""
    }

    private fun legDetail(from: CatalogAirport, to: CatalogAirport, summary: String, description: String): LegDetail {
        val pair = Regex("\\b${from.iata}\\b[^\\n]{0,40}?\\b${to.iata}\\b")
        val line = (listOf(summary) + description.split('\n')).firstOrNull { text ->
            pair.containsMatchIn(text) &&
                !SECRET_LINE.containsMatchIn(text) &&
                THREE_CAPS.findAll(text).map { it.value }.filter { byCode(it) != null }.toSet().size == 2
        } ?: return LegDetail("", emptyList())
        val numbers = FLIGHT_NUMBER.findAll(line).toList()
        return LegDetail(
            number = if (numbers.size == 1) "${numbers[0].groupValues[1]} ${numbers[0].groupValues[2]}" else "",
            times = CLOCK.findAll(line).map { it.value }.take(2).toList(),
        )
    }

    /** One candidate for a flight-like event, or null when the event is not about a flight. */
    fun parse(event: CalendarEvent): CalendarCandidate? {
        if (event.id.isEmpty() || event.status == "cancelled") return null
        if (event.eventType in SKIPPED_TYPES) return null
        val summary = clean(event.summary)
        val description = clean(event.description)
        val location = clean(event.location)
        if (CANCELLED.containsMatchIn(summary)) return null
        if (REMINDER.containsMatchIn(summary) && chains(summary).isEmpty()) return null

        val summaryRoutes = routes(summary)
        val detailsRoutes = routes(description)
        val complete = LinkedHashMap<String, Route>().apply {
            for (r in detailsRoutes + summaryRoutes) {
                if (r.from != null && r.to != null) {
                    val key = "${r.from.iata}|${r.to.iata}"
                    // JavaScript's Map keeps the first insertion position and the last value.
                    put(key, r)
                }
            }
        }.values.toList()

        val context = "$summary $description"
        if (!FLIGHT_CONTEXT.containsMatchIn(context) &&
            !context.contains('✈') &&
            !AIRLINE_NAMES.containsMatchIn(context) &&
            !FLIGHT_NUMBER.containsMatchIn(summary) &&
            chains(summary).isEmpty()
        ) {
            return null
        }

        // Legs: an explicit chain, or complete routes that connect in order.
        val chain = (chains(summary) + chains(description)).sortedByDescending { it.size }.firstOrNull()
        var legs: List<Route> = if (chain != null && chain.size > 2) {
            chain.drop(1).mapIndexed { i, to -> Route(chain[i], to) }
        } else {
            emptyList()
        }
        if (legs.isEmpty() && complete.size > 1) {
            val linked = mutableListOf(complete[0])
            for (r in complete.drop(1)) {
                if (r.from!!.iata == linked.last().to!!.iata && r.to!!.iata != linked.last().from!!.iata) linked += r
            }
            if (linked.size == complete.size) legs = linked
        }
        val multiLeg = legs.size > 1

        var from: CatalogAirport?
        var to: CatalogAirport?
        if (multiLeg) {
            from = legs.first().from
            to = legs.last().to
        } else {
            val r = complete.firstOrNull()
                ?: detailsRoutes.firstOrNull { it.from != null || it.to != null }
                ?: summaryRoutes.firstOrNull { it.from != null || it.to != null }
            from = r?.from
            to = r?.to
        }
        if (to == null) {
            DESTINATION.find(summary)?.let { to = resolve(it.groupValues[1]) }
        }
        if (from == null) {
            ORIGIN.find(summary)?.let { from = resolve(it.groupValues[1]) }
        }
        // A distinct, explicitly named airport location can fill a missing origin; it never overrides a route.
        if (from == null &&
            (to != null || FLIGHT_TO.containsMatchIn(summary)) &&
            (AIRPORT_WORD.containsMatchIn(location) || THREE_CAPS.containsMatchIn(location) || LOCATION_LABEL.containsMatchIn(location))
        ) {
            val departure = resolve(location.replaceFirst(LABEL_PREFIX, ""))
            if (departure != null && departure.iata != to?.iata) from = departure
        }

        val conflict = !multiLeg && complete.any { r ->
            r.from!!.iata != complete[0].from!!.iata || r.to!!.iata != complete[0].to!!.iata
        }
        val startInstant = instant(event.start)
        val endInstant = instant(event.end)
        val timed = startInstant != null && endInstant != null

        val issues = buildList {
            if (from == null) add(ReviewIssue.NO_FROM)
            if (to == null) add(ReviewIssue.NO_TO)
            if (from != null && to != null && from.iata == to.iata && !multiLeg) add(ReviewIssue.SAME_AIRPORT)
            if (!timed) add(ReviewIssue.UNTIMED)
            if (conflict) add(ReviewIssue.CONFLICT)
            if (multiLeg) add(ReviewIssue.MULTI_LEG)
        }
        val sameAirport = from != null && to != null && from.iata == to.iata
        val allDayDate = event.start?.date?.takeIf { ISO_DATE.matches(it) }
        val sortDate = startInstant?.toString() ?: allDayDate.orEmpty()
        return CalendarCandidate(
            sourceId = "$sourcePrefix${event.id}",
            from = from,
            to = to,
            legs = legs.map { it.from!!.iata to it.to!!.iata },
            legDetails = if (multiLeg) legs.map { legDetail(it.from!!, it.to!!, summary, description) } else emptyList(),
            startInstant = startInstant,
            endInstant = endInstant,
            departLocal = if (timed && from != null) localText(startInstant, from.tz) else "",
            arriveLocal = if (timed && to != null) localText(endInstant, to.tz) else "",
            number = flightNumber(summary, description),
            needsReview = from == null || to == null || !timed || conflict || multiLeg || sameAirport,
            requiresCorrection = conflict || multiLeg || sameAirport,
            issues = issues,
            sortDate = sortDate,
            allDayDate = allDayDate,
        )
    }

    /**
     * A multi-leg candidate becomes one candidate per leg, so each can be reviewed and added on its own.
     * Only stated times are kept: the event start is the first takeoff and the event end the last landing.
     */
    fun splitLegs(candidate: CalendarCandidate?): List<CalendarCandidate> {
        if (candidate == null) return emptyList()
        val count = candidate.legs.size
        if (count < 2) return listOf(candidate)
        return candidate.legs.mapIndexed { i, (fromCode, toCode) ->
            val from = byCode(fromCode)
            val to = byCode(toCode)
            val first = i == 0
            val last = i == count - 1
            val detail = candidate.legDetails.getOrNull(i) ?: LegDetail("", emptyList())
            val departLocal = if (first) candidate.departLocal else ""
            val arriveLocal = if (last) candidate.arriveLocal else ""
            val missing = when {
                departLocal.isNotEmpty() && arriveLocal.isEmpty() -> MissingTimes.LANDING
                arriveLocal.isNotEmpty() && departLocal.isEmpty() -> MissingTimes.TAKEOFF
                else -> MissingTimes.BOTH
            }
            val stated = if (detail.times.size == 2) detail.times else emptyList()
            candidate.copy(
                sourceId = "${candidate.sourceId}:leg${i + 1}",
                from = from,
                to = to,
                startInstant = if (first) candidate.startInstant else null,
                endInstant = if (last) candidate.endInstant else null,
                departLocal = departLocal,
                arriveLocal = arriveLocal,
                number = detail.number,
                needsReview = true,
                requiresCorrection = false,
                issues = if (stated.isNotEmpty()) listOf(ReviewIssue.STATED_TIMES) else emptyList(),
                leg = i + 1,
                legCount = count,
                missing = missing,
                statedTimes = stated,
            )
        }
    }

    /** [parse] then [splitLegs]: what the import screen lists for one event. */
    fun candidates(event: CalendarEvent): List<CalendarCandidate> = splitLegs(parse(event))

    companion object {
        const val DEVICE_SOURCE_PREFIX = "device:"

        // Calendar text comes from anyone who can send an invite, so it is capped before any regex work.
        private const val MAX_TEXT = 20000
        private const val MAX_LINE = 400
        private const val MAX_ROUTE_LINES = 100

        private val ENTITIES = mapOf(
            "&nbsp;" to " ", "&#160;" to " ", "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
            "&lt;" to "<", "&gt;" to ">", "&rarr;" to " → ", "&#8594;" to " → ", "&#x2192;" to " → ",
            "&ndash;" to " – ", "&#8211;" to " – ", "&mdash;" to " — ", "&#8212;" to " — ",
            "&middot;" to " · ", "&#183;" to " · ", "&bull;" to " · ", "&#8226;" to " · ",
        )

        private val SCRIPT_STYLE = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
        private val BLOCK_BREAK = Regex("<br\\s*/?\\s*>|</(p|div|tr|li|h[1-6])>", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]*>")
        private val ENTITY = Regex("&[a-z]+;|&#x?[0-9a-f]+;", RegexOption.IGNORE_CASE)
        private val PLANE = Regex("[\\u2708\\uFE0F]")
        private val SPACES = Regex("[ \\t]+")

        /** The JavaScript `clean`: HTML to text, entities decoded, capped. */
        internal fun clean(s: String?): String = (s ?: "")
            .take(MAX_TEXT)
            .replace(SCRIPT_STYLE, " ")
            .replace(BLOCK_BREAK, "\n")
            .replace(TAG, " ")
            .replace(ENTITY) { m -> ENTITIES[m.value.lowercase()] ?: m.value }
            .replace(PLANE, " ✈ ")
            .replace(SPACES, " ")
            .trim()

        private val COMBINING = Regex("[\\u0300-\\u036f]")
        internal fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase()

        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")
        internal fun plain(s: String): String = normalize(s).replace(NON_ALNUM, " ").replace(MULTI_SPACE, " ").trim()

        /** [term] occurs in [text] without a letter a–z directly before or after it. */
        internal fun boundary(text: String, term: String): Boolean {
            if (term.isEmpty()) return false
            var i = text.indexOf(term)
            while (i >= 0) {
                val before = i == 0 || text[i - 1] !in 'a'..'z'
                val end = i + term.length
                val after = end == text.length || text[end] !in 'a'..'z'
                if (before && after) return true
                i = text.indexOf(term, i + 1)
            }
            return false
        }

        // Airport and city aliases that are safe to map to one catalog airport.
        private val ALIASES: Map<String, List<String>> = mapOf(
            "BLR" to listOf("Bangalore", "Bengaluru", "Kempegowda"),
            "BOM" to listOf("Bombay", "Chhatrapati Shivaji"),
            "DEL" to listOf("Delhi", "New Delhi", "Indira Gandhi", "IGI"),
            "MAA" to listOf("Madras"),
            "CCU" to listOf("Calcutta", "Netaji Subhas", "Netaji Subhash"),
            "HYD" to listOf("Rajiv Gandhi", "Shamshabad"),
            "COK" to listOf("Cochin", "Nedumbassery"),
            "GOI" to listOf("Dabolim"),
            "GOX" to listOf("Mopa", "Manohar International"),
            "AMD" to listOf("Sardar Vallabhbhai"),
            "LKO" to listOf("Chaudhary Charan Singh"),
            "GAU" to listOf("Lokpriya Gopinath"),
            "BBI" to listOf("Biju Patnaik"),
            "PAT" to listOf("Jay Prakash Narayan", "Jayaprakash Narayan"),
            "IDR" to listOf("Devi Ahilya"),
            "NAG" to listOf("Babasaheb Ambedkar"),
            "VNS" to listOf("Lal Bahadur Shastri", "Benares", "Banaras"),
            "BEK" to listOf("Bareli"),
            "AYJ" to listOf("Maharishi Valmiki", "Faizabad"),
            "DXN" to listOf("Jewar", "Noida International"),
            "IXE" to listOf("Mangalore"),
            "TRV" to listOf("Trivandrum"),
            "VTZ" to listOf("Vizag", "Visakhapatnam"),
            "CCJ" to listOf("Calicut"),
            "TRZ" to listOf("Trichy", "Tiruchirapalli"),
            "BDQ" to listOf("Baroda"),
            "HBX" to listOf("Hubli"),
            "IXG" to listOf("Belgaum"),
            "MYQ" to listOf("Mysore"),
            "IXU" to listOf("Chhatrapati Sambhajinagar"),
            "IXD" to listOf("Allahabad"),
            "KUU" to listOf("Bhuntar"),
            "DHM" to listOf("Gaggal", "Kangra"),
            "AJL" to listOf("Lengpui"),
            "DPS" to listOf("Denpasar", "Ngurah Rai"),
            "CGK" to listOf("Soekarno", "Soekarno-Hatta"),
            "KUL" to listOf("KLIA"),
            "MNL" to listOf("Ninoy Aquino"),
            "SGN" to listOf("Tan Son Nhat", "Saigon"),
            "HAN" to listOf("Noi Bai"),
            "BKK" to listOf("Suvarnabhumi"),
            "DMK" to listOf("Don Mueang", "Don Muang"),
            "MLE" to listOf("Velana"),
            "CMB" to listOf("Bandaranaike"),
            "KTM" to listOf("Tribhuvan"),
            "DAC" to listOf("Hazrat Shahjalal"),
            "KHI" to listOf("Jinnah"),
            "LHE" to listOf("Allama Iqbal"),
            "LHR" to listOf("Heathrow", "London Heathrow"),
            "LGW" to listOf("Gatwick"),
            "STN" to listOf("Stansted"),
            "CDG" to listOf("Charles de Gaulle", "Roissy"),
            "ORY" to listOf("Orly"),
            "AMS" to listOf("Schiphol"),
            "FCO" to listOf("Fiumicino", "Leonardo da Vinci"),
            "MXP" to listOf("Malpensa"),
            "MAD" to listOf("Barajas"),
            "BCN" to listOf("El Prat"),
            "SVO" to listOf("Sheremetyevo"),
            "HND" to listOf("Haneda"),
            "NRT" to listOf("Narita"),
            "KIX" to listOf("Kansai"),
            "ICN" to listOf("Incheon"),
            "GMP" to listOf("Gimpo"),
            "PVG" to listOf("Pudong"),
            "SHA" to listOf("Hongqiao"),
            "PKX" to listOf("Daxing"),
            "JFK" to listOf("John F Kennedy", "John F. Kennedy", "Kennedy"),
            "LGA" to listOf("LaGuardia", "La Guardia"),
            "EWR" to listOf("Newark Liberty"),
            "BOS" to listOf("Logan"),
            "IAD" to listOf("Dulles"),
            "DCA" to listOf("Reagan National"),
            "ATL" to listOf("Hartsfield"),
            "ORD" to listOf("O'Hare", "OHare"),
            "SEA" to listOf("Sea-Tac", "SeaTac"),
            "YYZ" to listOf("Pearson"),
            "YUL" to listOf("Trudeau"),
            "SYD" to listOf("Kingsford Smith"),
            "MEL" to listOf("Tullamarine"),
            "NBO" to listOf("Jomo Kenyatta"),
            "ADD" to listOf("Bole"),
            "JNB" to listOf("O. R. Tambo", "OR Tambo", "O.R. Tambo"),
            "GRU" to listOf("Guarulhos"),
            "GIG" to listOf("Galeão", "Galeao"),
            "EZE" to listOf("Ezeiza"),
            "MEX" to listOf("Benito Juárez", "Benito Juarez"),
            "LIM" to listOf("Jorge Chávez", "Jorge Chavez"),
            "BOG" to listOf("El Dorado"),
            "PTY" to listOf("Tocumen"),
            "SIN" to listOf("Changi"),
            "DXB" to listOf("Dubai International"),
            "DOH" to listOf("Hamad"),
            "AUH" to listOf("Zayed International"),
            "RUH" to listOf("King Khalid"),
            "JED" to listOf("King Abdulaziz"),
            "SAW" to listOf("Sabiha", "Sabiha Gökçen", "Sabiha Gokcen"),
        )

        // City names that map to more than one airport, or where guessing is unsafe.
        private val AMBIGUOUS = setOf(
            "london", "paris", "tokyo", "osaka", "new york", "washington", "chicago", "houston", "dallas", "seoul",
            "bangkok", "istanbul", "rome", "milan", "moscow", "goa", "melbourne", "taipei", "toronto", "mumbai",
            "shanghai", "beijing", "buenos aires", "sao paulo", "rio de janeiro",
        )

        private val AIRLINE_NAMES = Regex(
            "\\b(indigo|air india|vistara|akasa|spicejet|air ?asia|alliance air|star air|emirates|etihad|qatar airways|flydubai|" +
                "air arabia|oman air|saudia|gulf air|kuwait airways|singapore airlines|scoot|malaysia airlines|thai airways|cathay|" +
                "japan airlines|ana\\b|korean air|asiana|china eastern|china southern|air china|turkish airlines|lufthansa|" +
                "swiss international|austrian airlines|klm|air france|british airways|virgin atlantic|aer lingus|iberia|vueling|" +
                "ryanair|easyjet|tap air|ita airways|finnair|norwegian|lot polish|aeroflot|united airlines|american airlines|delta|" +
                "southwest|jetblue|alaska airlines|air canada|westjet|aeromexico|latam|avianca|copa|qantas|jetstar|virgin australia|" +
                "air new zealand|fiji airways|ethiopian|kenya airways|egyptair|south african airways|royal jordanian|srilankan|" +
                "nepal airlines|biman|batik|garuda|lion air|vietjet|vietnam airlines|philippine airlines|cebu pacific|hk express|" +
                "eva air|china airlines|peach|jeju air|air busan)\\b",
            RegexOption.IGNORE_CASE,
        )

        private val AIRLINE_CODES = listOf(
            "6E", "AI", "UK", "SG", "IX", "QP", "I5", "G8", "S5", "9I", "2T", "SQ",
            "TR", "EK", "FZ", "EY", "QR", "WY", "G9", "SV", "GF", "KU", "RJ", "MS",
            "ET", "KQ", "SA", "UL", "RA", "BG", "BS", "MH", "AK", "D7", "OD", "GA",
            "QZ", "JT", "TG", "FD", "DD", "VZ", "VN", "VJ", "PR", "5J", "CX", "UO",
            "HX", "CI", "BR", "JL", "NH", "MM", "GK", "KE", "OZ", "7C", "TW", "LJ",
            "BX", "CA", "MU", "CZ", "HU", "3U", "TK", "PC", "LH", "LX", "OS", "SN",
            "EW", "AF", "KL", "BA", "VS", "EI", "IB", "VY", "FR", "U2", "TP", "AZ",
            "SK", "AY", "DY", "LO", "W6", "SU", "A3", "UA", "AA", "DL", "WN", "B6",
            "AS", "NK", "F9", "AC", "WS", "AM", "LA", "AV", "CM", "QF", "JQ", "VA",
            "NZ", "FJ", "TN",
        )

        private val FLIGHT_NUMBER = Regex("(?:^|[^A-Z0-9])(${AIRLINE_CODES.joinToString("|")})[ -]?(\\d{1,4})(?![0-9A-Z])")

        private val SECRET_LINE = Regex(
            "\\b(pnr|booking|reference|confirmation|record locator|locator|ticket|e-?ticket|passport|itinerary (?:no|number|id))\\b",
            RegexOption.IGNORE_CASE,
        )
        private const val CODE_SEPARATOR = "(?:→|->|–|—|>|\\bto\\b|\\bTO\\b|-|/)"
        private val CHAIN = Regex("\\b[A-Z]{3}(?:\\s*$CODE_SEPARATOR\\s*[A-Z]{3}\\b)+")
        private val NON_CAPS = Regex("[^A-Z]+")
        private val THREE_CAPS = Regex("\\b[A-Z]{3}\\b")

        private val DEPARTURE_LABEL = Regex(
            "(?:departure(?:\\s+(?:airport|city))?|depart(?:ing|s)?(?:\\s+from)?|origin|from)\\s*:\\s*([^\\n;|]+)",
            RegexOption.IGNORE_CASE,
        )
        private val ARRIVAL_LABEL = Regex(
            "(?:arrival(?:\\s+(?:airport|city))?|arriv(?:al|e|es|ing)?(?:\\s+(?:at|in))?|destination|to)\\s*:\\s*([^\\n;|]+)",
            RegexOption.IGNORE_CASE,
        )
        private val LINE_SPLIT = Regex("[\\n;|]")

        // JavaScript `$` without the m flag is the end of the input: \z here (Java's `$` also matches before a final \r or \n).
        private val ROUTE_LINE_1 = Regex(
            "^(?:✈\\s*)?(?:flight\\s+)?(?:from\\s+)?(.+?)\\s+(?:to|→|->|–|—|/|-|>)\\s+(.+)\\z",
            RegexOption.IGNORE_CASE,
        )
        private val ROUTE_LINE_2 = Regex("^(?:✈\\s*)?(?:flight\\s+)?(?:from\\s+)?(.+?)\\s*(?:→|->)\\s*(.+)\\z", RegexOption.IGNORE_CASE)
        private val ROUTE_LINE_3 = Regex("([a-z][a-z .']*[a-z])\\s?[-–]\\s?([a-z][a-z .']*[a-z])", RegexOption.IGNORE_CASE)
        private val TO_PREFIX = Regex("^to\\s", RegexOption.IGNORE_CASE)
        private val TO_PREFIX_REPLACE = Regex("^to\\s+", RegexOption.IGNORE_CASE)

        private val CLOCK = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
        private val SKIPPED_TYPES = setOf("birthday", "workingLocation", "outOfOffice", "focusTime")
        private val CANCELLED = Regex("\\b(cancelled|canceled)\\b", RegexOption.IGNORE_CASE)
        private val REMINDER = Regex("\\b(check[ -]?in|boarding pass|baggage|lounge)\\b", RegexOption.IGNORE_CASE)
        private val FLIGHT_CONTEXT = Regex("\\b(flight|flights|flying|fly|airways|airlines?|boarding|✈)\\b", RegexOption.IGNORE_CASE)
        private val DESTINATION = Regex("\\b(?:flight|flying|fly)\\s+to\\s+(.+)", RegexOption.IGNORE_CASE)
        private val ORIGIN = Regex("\\b(?:flight|flying|fly)\\s+from\\s+(.+?)(?:\\s+to\\s+.*)?\\z", RegexOption.IGNORE_CASE)
        private val FLIGHT_TO = Regex("\\b(?:flight|flying|fly)\\s+to\\b", RegexOption.IGNORE_CASE)
        private val AIRPORT_WORD = Regex("\\bairport\\b", RegexOption.IGNORE_CASE)
        private val LOCATION_LABEL = Regex("^\\s*(?:depart(?:ure)?|from)\\s*:", RegexOption.IGNORE_CASE)
        private val LABEL_PREFIX = Regex("^[^:]+:")
        private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val OFFSET_SUFFIX = Regex("(?:Z|[+-]\\d{2}:\\d{2})$")
        private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /** The event time as an instant: an offset date-time, or a local one in a valid zone. Null otherwise. */
        internal fun instant(part: EventTime?): Instant? {
            val v = part?.dateTime ?: return null
            return try {
                val withOffset = if (OFFSET_SUFFIX.containsMatchIn(v)) {
                    try {
                        Instant.fromEpochMilliseconds(OffsetDateTime.parse(v).toInstant().toEpochMilli())
                    } catch (e: java.time.DateTimeException) {
                        null
                    }
                } else {
                    null
                }
                withOffset ?: part.timeZone?.takeIf { it.isNotEmpty() }?.let { localToInstant(v.take(16), it) }
            } catch (e: java.time.DateTimeException) {
                null
            }
        }

        /** HeyTurbulence `localToUTC`: null for a malformed value or a wall time that does not exist (DST gap). */
        private fun localToInstant(local: String, tz: String): Instant? {
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$").matches(local)) return null
            val wall = LocalDateTime.parse(local)
            val zoned = wall.atZone(ZoneId.of(tz))
            if (zoned.toLocalDateTime() != wall) return null
            return Instant.fromEpochMilliseconds(zoned.toInstant().toEpochMilli())
        }

        /** HeyTurbulence `instantToLocal`: "2026-11-26T06:00" in [tz]. */
        internal fun localText(instant: Instant, tz: String): String = try {
            java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.of(tz)).format(LOCAL_MINUTES)
        } catch (e: java.time.DateTimeException) {
            ""
        }
    }
}
