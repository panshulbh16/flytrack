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
    /** [app.flytrack.core.model.TrackSource.wire]: pdf, screenshot, share, gmail, or null when typed by hand. */
    val prefillSource: String? = null,
    /** Seat from a boarding pass, e.g. "12A". */
    val prefillSeat: String? = null,
    /** [app.flytrack.feature.gmailimport.GmailSuggestion.id] when opened from a Gmail suggestion. */
    val prefillGmailSuggestionId: String? = null,
)

/** Detail of one flight; [flightId] is `flights.id` (what FCM sends as `flight_id`). */
@Serializable
data class FlightDetail(val flightId: String)

@Serializable
data object Settings

/** Ticket import (PDF, screenshot, text). Shared content is read from SharedContentHolder, not from arguments. */
@Serializable
data object ImportTicket

/** Import from Gmail: connect, scan, pick a suggestion. */
@Serializable
data object GmailImport
