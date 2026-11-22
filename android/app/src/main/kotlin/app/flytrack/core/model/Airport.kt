package app.flytrack.core.model

/** Reference data from `public.airports` (also embedded per flight in `flight_view`). */
data class Airport(
    val iata: String,
    val name: String,
    val city: String?,
    /** IANA zone id, e.g. "Asia/Kolkata". All times of this airport are shown in this zone. */
    val tz: String,
    val lat: Double,
    val lon: Double,
) {
    /** "Delhi" when the city is known, otherwise the airport name. */
    val displayCity: String get() = city?.takeIf { it.isNotBlank() } ?: name
}
