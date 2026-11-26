package app.flytrack.core.model

import kotlinx.datetime.LocalDate

/**
 * P3 B5: "that day's departures from [dep]", optionally only those to [arr] and on [airline]
 * (body of the `search-route` Edge Function).
 */
data class RouteSearchQuery(
    /** IATA code of the departure airport, e.g. "DEL". */
    val dep: String,
    /** IATA code of the arrival airport, or null for every destination. */
    val arr: String? = null,
    /** Local departure date at [dep]. */
    val date: LocalDate,
    /** Two-character airline code ("6E"), or null for every airline. */
    val airline: String? = null,
) {
    /** The same rules as the server's `normalizeRouteQuery`, on the upper-cased values. */
    val isValid: Boolean
        get() = IATA.matches(dep) && (arr == null || (IATA.matches(arr) && arr != dep)) && (airline == null || AIRLINE.matches(airline))

    /** Upper-cased and trimmed, blank optional values dropped. */
    fun normalized(): RouteSearchQuery = RouteSearchQuery(
        dep = dep.trim().uppercase(),
        arr = arr?.trim()?.uppercase()?.ifEmpty { null },
        date = date,
        airline = airline?.trim()?.uppercase()?.ifEmpty { null },
    )

    companion object {
        private val IATA = Regex("^[A-Z0-9]{3}$")
        private val AIRLINE = Regex("^[A-Z0-9]{2}$")

        /** A typed airline code: up to two letters or digits, upper-cased. */
        fun sanitizeAirlineInput(input: String): String = input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(2)
    }
}
