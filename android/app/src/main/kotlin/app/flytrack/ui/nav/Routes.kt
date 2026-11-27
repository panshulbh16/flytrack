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
    /** P3 A3: a calendar suggestion without a flight number opens the "By route" tab from this airport (IATA). */
    val prefillFrom: String? = null,
    /** Arrival airport (IATA) of that suggestion. */
    val prefillTo: String? = null,
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

/** FlyTrack Pro paywall (plans from Google Play via RevenueCat, restore). Opened through LocalPaywallLauncher. */
@Serializable
data object Paywall

/** Passport and Year in Review: the user's flown flights, counted by `my_passport`. */
@Serializable
data object Passport

/** Friends: invite code, list with alert switches (P2 wave 2). */
@Serializable
data object Friends

/** "Enter a friend code"; [prefillCode] when the code came from outside. */
@Serializable
data class AcceptFriendInvite(val prefillCode: String? = null)

/** Email forwarding address and how-to (P2 wave 2, Pro). */
@Serializable
data object EmailForwarding

/** P3 A3: flights found in the device calendar (Pro). */
@Serializable
data object CalendarImport

/** P3 B10: "You vs Asha" Passport comparison. */
@Serializable
data class FriendPassport(val friendId: String, val friendName: String? = null)

/** P3 B13: family sharing; [prefillCode] when a family code came from outside. */
@Serializable
data class Family(val prefillCode: String? = null)

/**
 * P4 radar. [iata] = ground radar of that airport; [lat]/[lon] = planes around that point (the user's
 * flight; decimal strings, as navigation arguments); neither = "Planes near me" (coarse location, asked on tap).
 */
@Serializable
data class Radar(val iata: String? = null, val lat: String? = null, val lon: String? = null)

/** P4 terminal map of [iata] from the bundled OpenStreetMap extract; [gate]/[belt] are highlighted when known. */
@Serializable
data class TerminalMap(val iata: String, val gate: String? = null, val belt: String? = null, val flightId: String? = null)

/** P4 "Redeem a gift code"; [prefillCode] when the code came from outside. */
@Serializable
data class RedeemGift(val prefillCode: String? = null)

/** P4 "My gifts": the Gift Pro codes the user bought. */
@Serializable
data object MyGifts
