package app.flytrack.wear.ui

/** The strings of one complication, for every type the data source offers. Pure. */
data class ComplicationTexts(
    /** SHORT_TEXT text: "18:25" (or the flight number when no time is known). */
    val shortText: String,
    /** SHORT_TEXT title: "DEL-BOM". */
    val shortTitle: String,
    /** LONG_TEXT text: "6E 2341 · DEL → BOM". */
    val longText: String,
    /** LONG_TEXT title: "Delayed 25 min · 18:25 · Gate 42B". */
    val longTitle: String,
    /** What TalkBack reads. */
    val contentDescription: String,
) {
    companion object {
        fun from(flight: WatchFlight, strings: WearStrings): ComplicationTexts {
            val title = strings.join(listOfNotNull(flight.statusText, flight.departureTime, flight.gateText))
            val text = strings.join(listOf(flight.designator, flight.route))
            return ComplicationTexts(
                shortText = flight.departureTime ?: flight.designator,
                shortTitle = flight.shortRoute,
                longText = text,
                longTitle = title,
                contentDescription = strings.join(listOfNotNull(flight.designator, flight.route, flight.statusText, flight.departureTime, flight.dateText, flight.gateText)),
            )
        }
    }
}
