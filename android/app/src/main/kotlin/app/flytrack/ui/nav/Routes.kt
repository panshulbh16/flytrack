package app.flytrack.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe destinations of the single-activity NavHost (Navigation Compose, kotlinx.serialization). */

/** Start destination: the list of tracked flights. */
@Serializable
data object Flights

/**
 * Add-flight screen. The prefill values come from the ticket importer:
 * @property prefillFlight free text such as "6E 2341"
 * @property prefillDate ISO local date "2026-11-25"
 * @property prefillPnr booking reference
 */
@Serializable
data class AddFlight(
    val prefillFlight: String? = null,
    val prefillDate: String? = null,
    val prefillPnr: String? = null,
)

/** Detail of one flight; [flightId] is `flights.id` (what FCM sends as `flight_id`). */
@Serializable
data class FlightDetail(val flightId: String)

@Serializable
data object Settings

/** Ticket import (PDF, screenshot, text). Shared content is read from SharedContentHolder, not from arguments. */
@Serializable
data object ImportTicket
