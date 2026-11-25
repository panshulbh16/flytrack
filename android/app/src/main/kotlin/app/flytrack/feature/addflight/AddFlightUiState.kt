package app.flytrack.feature.addflight

import app.flytrack.core.model.Flight
import app.flytrack.core.model.TrackSource
import kotlinx.datetime.LocalDate

/** Values handed over by the ticket importer (all optional, all untrusted OCR output). */
data class AddFlightPrefill(
    /** Flight number text, e.g. "6E 2341". */
    val flight: String? = null,
    /** ISO local departure date, e.g. "2026-11-25". */
    val date: String? = null,
    /** Booking reference. */
    val pnr: String? = null,
    /** How the ticket arrived. Null keeps the old rule: a prefill is a share, an empty form is manual. */
    val source: TrackSource? = null,
    /** Seat from a boarding pass. */
    val seat: String? = null,
    /** The Gmail suggestion this form came from: marked added (and its notification cleared) once the flight is tracked. */
    val gmailSuggestionId: String? = null,
) {
    val isEmpty: Boolean get() = flight.isNullOrBlank() && date.isNullOrBlank() && pnr.isNullOrBlank()
}

/** What one search asked for: the normalised flight number ("6E 2341") and the departure date. */
data class SearchQuery(val flight: String, val date: LocalDate)

/** Why the flight-number field shows an error. */
enum class FlightInputError {
    /** Search was pressed with an empty or unfinished field. */
    REQUIRED,

    /** The text cannot be (or become) a flight number. */
    NOT_A_FLIGHT_NUMBER,
}

/** A note above the form when it was filled in from a ticket. */
sealed interface PrefillNotice {
    /** Everything was readable; the search already runs. */
    data object CheckDetails : PrefillNotice

    data object FlightUnreadable : PrefillNotice

    data object DateUnreadable : PrefillNotice

    /** The ticket's date is outside what the backend can search (an old or far-future ticket). */
    data class DateOutOfRange(val ticketDate: LocalDate) : PrefillNotice
}

/** The area below the form. */
sealed interface SearchUiState {
    data object Idle : SearchUiState

    data class Searching(val query: SearchQuery) : SearchUiState

    /** One entry per leg, sorted by departure. Never empty. */
    data class Results(val query: SearchQuery, val legs: List<Flight>) : SearchUiState

    data class NotFound(val query: SearchQuery) : SearchUiState

    /** The per-user search limit (counted over the last 24 hours) is used up. */
    data object RateLimited : SearchUiState

    /** The flight-data provider or our backend failed; retrying later helps. */
    data object ProviderError : SearchUiState

    data object Offline : SearchUiState

    /** This build has no backend keys. */
    data object NotConfigured : SearchUiState

    /** The server rejected the input for a reason this app version cannot explain. */
    data object Rejected : SearchUiState
}

/** Why tapping "Track" (or "Open") on a leg did not finish. */
sealed interface TrackProblem {
    /** [limit] active flights are allowed on the user's plan; [proLimit] is non-null for free users. */
    data class LimitReached(val limit: Int, val proLimit: Int?) : TrackProblem

    data object Offline : TrackProblem

    /** The flight row vanished on the server: the search result is stale. */
    data object FlightGone : TrackProblem

    data object NotConfigured : TrackProblem

    /** The PNR or seat typed above is not acceptable; the field shows the details. */
    data object InvalidDetails : TrackProblem

    /** The flight is tracked already, but the PNR/seat typed above could not be saved to it. */
    data object DetailsNotSaved : TrackProblem

    data object Failed : TrackProblem
}

/** Shown under the leg it belongs to. */
data class TrackNotice(val flightId: String, val problem: TrackProblem)

data class AddFlightUiState(
    val flightInput: String = "",
    /** "6E 2341" while [flightInput] is a complete flight number, else null. */
    val normalisedFlight: String? = null,
    val flightError: FlightInputError? = null,

    /** Local departure date at the origin airport. */
    val date: LocalDate,
    /** Today on this phone: the anchor of the Yesterday / Today / Tomorrow shortcuts. */
    val today: LocalDate,
    val firstSelectableDate: LocalDate,
    val lastSelectableDate: LocalDate,
    /** The server refused [date]; cleared when the date changes. */
    val dateRejected: Boolean = false,

    val pnrInput: String = "",
    val pnrInvalid: Boolean = false,
    val seatInput: String = "",
    val seatInvalid: Boolean = false,

    val prefillNotice: PrefillNotice? = null,
    /** The form was opened by the ticket importer, so offering "Import from a ticket" again would loop. */
    val isFromTicket: Boolean = false,
    val search: SearchUiState = SearchUiState.Idle,
    /** Flights the user tracks already (archived ones excluded): those legs offer "Open" instead of "Track". */
    val trackedFlightIds: Set<String> = emptySet(),
    /** The leg whose Track/Open request is running. Stays set after success, until the screen closes. */
    val trackingFlightId: String? = null,
    val trackNotice: TrackNotice? = null,
    /** False while the build has no backend keys: nothing can be searched. */
    val isBackendConfigured: Boolean = true,
    /** True for an anonymous user who could link Google, which raises the daily search limit. */
    val canRaiseSearchLimit: Boolean = false,
) {
    val isSearching: Boolean get() = search is SearchUiState.Searching
    val isTracking: Boolean get() = trackingFlightId != null

    /** Yesterday is not always searchable: the server's window is counted in UTC. */
    fun isSelectable(day: LocalDate): Boolean = day in firstSelectableDate..lastSelectableDate

    fun isTracked(flightId: String): Boolean = flightId in trackedFlightIds
}

/** One-shot events for the Route. */
sealed interface AddFlightEvent {
    /** The flight is tracked (or was already): leave the flow and show its detail screen. */
    data class Finished(val flightId: String) : AddFlightEvent
}
