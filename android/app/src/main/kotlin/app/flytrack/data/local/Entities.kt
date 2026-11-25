package app.flytrack.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlin.time.Instant

/**
 * Cache of `public.flight_view` rows. Column names equal the view's column names; the embedded
 * airport columns of the view (dep_name, dep_tz, ...) live in [AirportEntity] instead.
 */
@Entity(tableName = "flights")
data class FlightEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "carrier_iata") val carrierIata: String,
    @ColumnInfo(name = "flight_number") val flightNumber: String,
    /** ISO date "2026-11-25": local departure date at the origin. */
    @ColumnInfo(name = "flight_date") val flightDate: String,
    @ColumnInfo(name = "dep_iata", index = true) val depIata: String,
    @ColumnInfo(name = "arr_iata", index = true) val arrIata: String,
    @ColumnInfo(name = "airline_name") val airlineName: String?,
    /** Server wire value, e.g. "en_route". */
    val status: String,
    @ColumnInfo(name = "sched_dep") val schedDep: Instant?,
    @ColumnInfo(name = "est_dep") val estDep: Instant?,
    @ColumnInfo(name = "act_dep") val actDep: Instant?,
    @ColumnInfo(name = "dep_terminal") val depTerminal: String?,
    @ColumnInfo(name = "dep_gate") val depGate: String?,
    @ColumnInfo(name = "dep_checkin") val depCheckin: String?,
    @ColumnInfo(name = "sched_arr") val schedArr: Instant?,
    @ColumnInfo(name = "est_arr") val estArr: Instant?,
    @ColumnInfo(name = "act_arr") val actArr: Instant?,
    @ColumnInfo(name = "arr_terminal") val arrTerminal: String?,
    @ColumnInfo(name = "arr_gate") val arrGate: String?,
    @ColumnInfo(name = "baggage_belt") val baggageBelt: String?,
    @ColumnInfo(name = "aircraft_model") val aircraftModel: String?,
    @ColumnInfo(name = "aircraft_reg") val aircraftReg: String?,
    @ColumnInfo(name = "delay_reason") val delayReason: String?,
    @ColumnInfo(name = "live_lat") val liveLat: Double?,
    @ColumnInfo(name = "live_lon") val liveLon: Double?,
    @ColumnInfo(name = "live_alt_ft") val liveAltFt: Int?,
    @ColumnInfo(name = "live_heading") val liveHeading: Double?,
    @ColumnInfo(name = "live_speed_kts") val liveSpeedKts: Double?,
    @ColumnInfo(name = "live_updated_at") val liveUpdatedAt: Instant?,
    @ColumnInfo(name = "last_polled_at") val lastPolledAt: Instant?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant?,
    @ColumnInfo(name = "inbound_flight_id") val inboundFlightId: String?,
    /** The view's `prediction` jsonb as text, null for free users. */
    val prediction: String?,
    @ColumnInfo(name = "has_pro_insight") val hasProInsight: Boolean,
)

/** Cache of `public.user_flights` rows of the signed-in user. Column names equal the table's. */
@Entity(tableName = "user_flights", indices = [Index("flight_id")])
data class TrackedFlightEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "flight_id") val flightId: String,
    val pnr: String?,
    val seat: String?,
    val cabin: String?,
    val source: String,
    /** Raw server map, including keys this app version does not know, so updates never drop them. */
    @ColumnInfo(name = "notify_prefs") val notifyPrefs: Map<String, Boolean>,
    val note: String?,
    @ColumnInfo(name = "archived_at") val archivedAt: Instant?,
    @ColumnInfo(name = "created_at") val createdAt: Instant?,
)

/** Cache of airports, filled from `flight_view` rows and from `public.airports` on demand. */
@Entity(tableName = "airports")
data class AirportEntity(
    @PrimaryKey val iata: String,
    val name: String,
    val city: String?,
    val tz: String,
    val lat: Double,
    val lon: Double,
)

/** Cache of the signed-in user's `public.profiles` row. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val plan: String,
    @ColumnInfo(name = "pro_until") val proUntil: Instant?,
)

/** A flight with both airports (null until the airport is cached). */
data class FlightWithAirports(
    @Embedded val flight: FlightEntity,
    @Relation(parentColumn = "dep_iata", entityColumn = "iata") val depAirport: AirportEntity?,
    @Relation(parentColumn = "arr_iata", entityColumn = "iata") val arrAirport: AirportEntity?,
)

/** A tracked flight joined with its flight and airports. */
data class TrackedFlightWithFlight(
    @Embedded val tracking: TrackedFlightEntity,
    @Relation(parentColumn = "flight_id", entityColumn = "id", entity = FlightEntity::class)
    val flight: FlightWithAirports,
)

/**
 * A flight found in the user's Gmail. Local-only state, never sent to the server. No email body
 * is stored, only what the suggestion shows.
 */
@Entity(tableName = "gmail_suggestions")
data class GmailSuggestionEntity(
    /** "6E2341_2026-12-03": compact flight number and date. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    /** Display form, e.g. "6E 2341". */
    val flight: String,
    /** ISO date "2026-12-03". */
    val date: String,
    @ColumnInfo(name = "from_iata") val fromIata: String?,
    @ColumnInfo(name = "to_iata") val toIata: String?,
    val pnr: String?,
    val subject: String,
    /** SuggestionStatus wire value: "pending", "dismissed" or "added". */
    val status: String,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "found_at") val foundAt: Long,
)

/** A Gmail message id already read (or skipped), so later scans do not fetch it again. */
@Entity(tableName = "gmail_seen_messages")
data class GmailSeenMessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
)
