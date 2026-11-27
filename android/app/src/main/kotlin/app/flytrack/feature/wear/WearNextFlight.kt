package app.flytrack.feature.wear

import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.departureDelayMinutes
import app.flytrack.core.model.statusTone
import app.flytrack.feature.live.LiveContentFormatter
import app.flytrack.feature.live.LiveFlightSelector
import app.flytrack.feature.live.LiveStatusWord
import app.flytrack.feature.live.liveProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * P3 B9: what the phone puts on the Wearable Data Layer at [PATH] for the `:wear` Tile and
 * complication. It is the compact flight shape of the sync push (`compactFlight` in
 * `supabase/functions/_shared/notify.ts`: same short keys and meanings) plus what only the phone
 * knows: the airports' time zones and the status word the phone widget shows.
 *
 * The watch (`app.flytrack.wear.data.NextFlightPayload`) decodes the same keys; `v` is bumped on an
 * incompatible change. Live position and `u` (updated_at) are left out on purpose: they change on
 * every poll, and the watch should only wake when something it draws changes.
 */
@Serializable
data class WearNextFlight(
    val v: Int = VERSION,
    val id: String,
    /** Carrier IATA, "6E". */
    val c: String,
    /** Flight number, "2341". */
    val n: String,
    /** Local departure date at the origin, "2026-11-25". */
    val d: String,
    val dep: String,
    val arr: String,
    /** Server status (`FlightStatus.wire`). */
    val st: String,
    val sd: String? = null,
    val ed: String? = null,
    val ad: String? = null,
    val sa: String? = null,
    val ea: String? = null,
    val aa: String? = null,
    val dt: String? = null,
    val dg: String? = null,
    val at: String? = null,
    val ag: String? = null,
    val bb: String? = null,
    /** IANA zone of the departure airport; null when the airport is unknown (the watch then uses its own zone). */
    val dtz: String? = null,
    val atz: String? = null,
    /** The phone widget's status word ([LiveStatusWord] lower-cased: "on_time", "delayed", "in_air", ...). */
    val sw: String,
    /** Departure delay in minutes when known (negative = early). */
    val dm: Int? = null,
    /** Colour family ([FlightStatusTone] lower-cased). */
    val tone: String,
    /** True when the phone has no fresh data and the times are its offline estimate. */
    val est: Boolean = false,
) {
    companion object {
        const val PATH = "/next_flight"
        const val VERSION = 1

        private val json = Json {
            explicitNulls = false
            encodeDefaults = true
        }

        fun encode(payload: WearNextFlight): ByteArray = json.encodeToString(serializer(), payload).encodeToByteArray()

        fun decode(bytes: ByteArray): WearNextFlight = json.decodeFromString(serializer(), bytes.decodeToString())

        /**
         * The watch's flight: the same one the Next flight widget shows (free for everyone), so the
         * phone and the watch never disagree. Null when there is nothing to show.
         */
        fun select(tracked: List<TrackedFlightDetails>, now: Instant): WearNextFlight? {
            val flight = LiveFlightSelector.selectWidget(tracked, now) ?: return null
            val progress = liveProgress(flight, now)
            val word = LiveContentFormatter.statusWord(flight, progress)
            val estimated = progress?.estimated == true
            val tone = if (estimated) FlightStatusTone.ESTIMATED else flight.statusTone
            return WearNextFlight(
                id = flight.id,
                c = flight.carrierIata,
                n = flight.flightNumber,
                d = flight.flightDate.toString(),
                dep = flight.depIata,
                arr = flight.arrIata,
                st = flight.status.wire,
                sd = flight.schedDep?.toString(),
                ed = flight.estDep?.toString(),
                ad = flight.actDep?.toString(),
                sa = flight.schedArr?.toString(),
                ea = flight.estArr?.toString(),
                aa = flight.actArr?.toString(),
                dt = flight.depTerminal,
                dg = flight.depGate,
                at = flight.arrTerminal,
                ag = flight.arrGate,
                bb = flight.baggageBelt,
                dtz = flight.depTz,
                atz = flight.arrTz,
                sw = word.wire,
                dm = flight.departureDelayMinutes,
                tone = tone.name.lowercase(),
                est = estimated,
            )
        }

        private val LiveStatusWord.wire: String get() = name.lowercase()
    }
}

/** What the publisher has to do with the Data Layer for a new state. */
sealed interface WearPublishAction {
    data class Put(val payload: WearNextFlight) : WearPublishAction
    data object Delete : WearPublishAction
    data object Skip : WearPublishAction
}

/**
 * Remembers what reached the Data Layer last, so the minute ticker of the live surfaces does not
 * rewrite (and wake the watch) when nothing the watch draws changed. Pure; not thread-safe (the
 * caller serialises calls).
 */
class WearPublishGate {
    private var known = false
    private var last: WearNextFlight? = null

    fun decide(next: WearNextFlight?): WearPublishAction = when {
        known && next == last -> WearPublishAction.Skip
        next == null -> WearPublishAction.Delete
        else -> WearPublishAction.Put(next)
    }

    /** Called after the Data Layer accepted [published]. */
    fun committed(published: WearNextFlight?) {
        known = true
        last = published
    }

    /** Forget the last state: the next [decide] always writes (for example after the watch app was reinstalled). */
    fun reset() {
        known = false
        last = null
    }
}
