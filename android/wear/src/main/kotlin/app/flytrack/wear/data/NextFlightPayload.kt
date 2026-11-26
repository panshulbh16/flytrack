package app.flytrack.wear.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Data Layer item the phone writes at [PATH] (`app.flytrack.feature.wear.WearNextFlight` in
 * `:app`): the sync push's compact flight keys plus the airports' time zones and the phone widget's
 * status word. Times are ISO-8601 UTC strings. Unknown keys are ignored, so the phone may add some.
 */
@Serializable
data class NextFlightPayload(
    val v: Int = 1,
    val id: String,
    val c: String,
    val n: String,
    val d: String? = null,
    val dep: String,
    val arr: String,
    val st: String = "unknown",
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
    val dtz: String? = null,
    val atz: String? = null,
    val sw: String? = null,
    val dm: Int? = null,
    val tone: String? = null,
    val est: Boolean = false,
) {
    companion object {
        const val PATH = "/next_flight"

        /** The newest payload version this watch app understands. */
        const val SUPPORTED_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Null for anything this app cannot show (malformed, or a newer incompatible version). */
        fun decode(bytes: ByteArray?): NextFlightPayload? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                json.decodeFromString(serializer(), bytes.decodeToString()).takeIf { it.v <= SUPPORTED_VERSION }
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                null
            }
        }
    }
}
