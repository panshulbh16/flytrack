package app.flytrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One row of `public.flight_view`. Property names map 1:1 to the view's columns.
 * Timestamps stay ISO strings here and are parsed leniently by the mappers.
 */
@Serializable
data class FlightViewDto(
    val id: String,
    @SerialName("carrier_iata") val carrierIata: String,
    @SerialName("flight_number") val flightNumber: String,
    @SerialName("flight_date") val flightDate: String,
    @SerialName("dep_iata") val depIata: String,
    @SerialName("arr_iata") val arrIata: String,
    @SerialName("airline_name") val airlineName: String? = null,
    val status: String = "unknown",
    @SerialName("sched_dep") val schedDep: String? = null,
    @SerialName("est_dep") val estDep: String? = null,
    @SerialName("act_dep") val actDep: String? = null,
    @SerialName("dep_terminal") val depTerminal: String? = null,
    @SerialName("dep_gate") val depGate: String? = null,
    @SerialName("dep_checkin") val depCheckin: String? = null,
    @SerialName("sched_arr") val schedArr: String? = null,
    @SerialName("est_arr") val estArr: String? = null,
    @SerialName("act_arr") val actArr: String? = null,
    @SerialName("arr_terminal") val arrTerminal: String? = null,
    @SerialName("arr_gate") val arrGate: String? = null,
    @SerialName("baggage_belt") val baggageBelt: String? = null,
    @SerialName("aircraft_model") val aircraftModel: String? = null,
    @SerialName("aircraft_reg") val aircraftReg: String? = null,
    @SerialName("delay_reason") val delayReason: String? = null,
    @SerialName("live_lat") val liveLat: Double? = null,
    @SerialName("live_lon") val liveLon: Double? = null,
    @SerialName("live_alt_ft") val liveAltFt: Int? = null,
    @SerialName("live_heading") val liveHeading: Double? = null,
    @SerialName("live_speed_kts") val liveSpeedKts: Double? = null,
    @SerialName("live_updated_at") val liveUpdatedAt: String? = null,
    @SerialName("last_polled_at") val lastPolledAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("dep_name") val depName: String? = null,
    @SerialName("dep_city") val depCity: String? = null,
    @SerialName("dep_lat") val depLat: Double? = null,
    @SerialName("dep_lon") val depLon: Double? = null,
    @SerialName("dep_tz") val depTz: String? = null,
    @SerialName("arr_name") val arrName: String? = null,
    @SerialName("arr_city") val arrCity: String? = null,
    @SerialName("arr_lat") val arrLat: Double? = null,
    @SerialName("arr_lon") val arrLon: Double? = null,
    @SerialName("arr_tz") val arrTz: String? = null,
    @SerialName("inbound_flight_id") val inboundFlightId: String? = null,
    val prediction: JsonObject? = null,
    /** SQL can yield NULL here (prediction without lateMin), which means "no insight". */
    @SerialName("has_pro_insight") val hasProInsight: Boolean? = null,
)

/** `flight_view.prediction` after the server removed its internal keys. */
@Serializable
data class PredictionDto(
    val computedAt: String? = null,
    val predictedDep: String? = null,
    val lateMin: Int = 0,
    val reason: String? = null,
    val inbound: InboundDto? = null,
)

@Serializable
data class InboundDto(
    val flight: String = "",
    val from: String = "",
    val status: String = "unknown",
    val schedArr: String? = null,
    val estArr: String? = null,
    val actArr: String? = null,
    val live: LivePositionDto? = null,
)

/** `LivePosition` as the backend serialises it inside the prediction. */
@Serializable
data class LivePositionDto(
    val lat: Double,
    val lon: Double,
    val altFt: Int? = null,
    val headingDeg: Double? = null,
    val speedKts: Double? = null,
    val at: String? = null,
)

/** One row of `public.user_flights`. */
@Serializable
data class UserFlightDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("flight_id") val flightId: String,
    val pnr: String? = null,
    val seat: String? = null,
    val cabin: String? = null,
    val source: String = "manual",
    @SerialName("notify_prefs") val notifyPrefs: Map<String, Boolean> = emptyMap(),
    val note: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Body of `POST /rest/v1/user_flights`. `user_id` is filled by the column default `auth.uid()`;
 * null fields are omitted so the other column defaults apply.
 */
@Serializable
data class UserFlightInsertDto(
    @SerialName("flight_id") val flightId: String,
    val pnr: String? = null,
    val seat: String? = null,
    val cabin: String? = null,
    val source: String = "manual",
    val note: String? = null,
)

/** One row of `public.airports`. */
@Serializable
data class AirportDto(
    val iata: String,
    val icao: String? = null,
    val name: String,
    val city: String? = null,
    val country: String? = null,
    val lat: Double,
    val lon: Double,
    val tz: String,
)

/** One row of `public.profiles`. */
@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val plan: String = "free",
    @SerialName("pro_until") val proUntil: String? = null,
)

/** Body of `POST /functions/v1/search-flight`. */
@Serializable
data class SearchFlightRequest(
    val flight: String,
    /** "YYYY-MM-DD": local departure date. */
    val date: String,
)

/** 200 response of `search-flight`. */
@Serializable
data class SearchFlightResponse(val flights: List<FlightViewDto> = emptyList())

/**
 * Error body of the Edge Functions: `{ "error": "<code>", "message": "<English text>" }`.
 * search-flight codes: bad_input (400), unauthorized (401), not_found (404), rate_limited (429),
 * internal (500), provider_error (502).
 */
@Serializable
data class FunctionErrorDto(
    val error: String? = null,
    val message: String? = null,
)

/** Arguments of the `register_device` RPC, named exactly like the SQL function's parameters. */
@Serializable
data class RegisterDeviceParams(
    @SerialName("p_token") val token: String,
    @SerialName("p_app_version") val appVersion: String? = null,
    @SerialName("p_os_version") val osVersion: String? = null,
    @SerialName("p_locale") val locale: String? = null,
    @SerialName("p_timezone") val timezone: String? = null,
)

/** Arguments of the `unregister_device` RPC. */
@Serializable
data class UnregisterDeviceParams(@SerialName("p_token") val token: String)

/**
 * The `flight` value of an FCM sync message: `compactFlight()` in notify.ts.
 * It carries no airport details and no Pro fields; those are kept from the cached row.
 */
@Serializable
data class CompactFlightDto(
    val id: String,
    @SerialName("c") val carrierIata: String,
    @SerialName("n") val flightNumber: String,
    @SerialName("d") val flightDate: String,
    @SerialName("dep") val depIata: String,
    @SerialName("arr") val arrIata: String,
    @SerialName("st") val status: String = "unknown",
    @SerialName("sd") val schedDep: String? = null,
    @SerialName("ed") val estDep: String? = null,
    @SerialName("ad") val actDep: String? = null,
    @SerialName("sa") val schedArr: String? = null,
    @SerialName("ea") val estArr: String? = null,
    @SerialName("aa") val actArr: String? = null,
    @SerialName("dt") val depTerminal: String? = null,
    @SerialName("dg") val depGate: String? = null,
    @SerialName("at") val arrTerminal: String? = null,
    @SerialName("ag") val arrGate: String? = null,
    @SerialName("bb") val baggageBelt: String? = null,
    @SerialName("ac") val aircraftModel: String? = null,
    @SerialName("reg") val aircraftReg: String? = null,
    @SerialName("lat") val liveLat: Double? = null,
    @SerialName("lon") val liveLon: Double? = null,
    @SerialName("alt") val liveAltFt: Int? = null,
    @SerialName("lu") val liveUpdatedAt: String? = null,
    @SerialName("u") val updatedAt: String? = null,
)
