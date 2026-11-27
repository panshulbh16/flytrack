package app.flytrack.feature.tripit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URI
import java.net.URLDecoder
import kotlin.time.Instant

/**
 * TripIt sync (docs/P4_CONTRACTS.md section 2 and the notes in section 7). Pure Kotlin, JVM-tested.
 */
object TripItContract {
    const val RPC_STATUS = "tripit_status"
    const val FUNCTION_CONNECT = "tripit-connect"
    const val FUNCTION_SYNC = "tripit-sync"
    const val FUNCTION_DISCONNECT = "tripit-disconnect"

    /** POST {code}: finishes the connect for the signed-in user (login CSRF fix, migration 35). */
    const val FUNCTION_COMPLETE = "tripit-complete"

    const val SCHEME = "heygate"
    const val HOST = "tripit"
    const val PATH_CONNECTED = "/connected"
    const val PATH_FAILED = "/failed"

    /** `heygate://tripit/callback?code=...`: the one-time code tripit-callback hands the app. */
    const val PATH_CALLBACK = "/callback"

    /** tripit-callback's code: 32 random bytes, base64url without padding. */
    val CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
}

/** `last_error` of `tripit_status()`. */
enum class TripItLastError { REVOKED, TRIPIT_ERROR }

data class TripItStatus(val connected: Boolean, val lastSyncAt: Instant?, val lastError: TripItLastError?)

/** `tripit-sync`'s answer. */
@Serializable
data class TripItSyncSummary(
    val segments: Int = 0,
    val added: Int = 0,
    val existing: Int = 0,
    @SerialName("not_found") val notFound: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
)

/** Typed outcome of a TripIt call. */
sealed interface TripItResult<out T> {
    data class Ok<T>(val value: T) : TripItResult<T>

    /** `pro_required` (402). */
    data object ProRequired : TripItResult<Nothing>

    /** `not_connected` (409). */
    data object NotConnected : TripItResult<Nothing>

    /** `tripit_revoked` (409): the user revoked access in TripIt; offer "Connect again". */
    data object Revoked : TripItResult<Nothing>

    /** `tripit-complete` `not_your_link` (403): the TripIt link was started from another HeyGate account. */
    data object NotYourLink : TripItResult<Nothing>

    /** `tripit-complete` `expired` (410): the code is unknown, used or older than 10 minutes. */
    data object Expired : TripItResult<Nothing>

    /** `tripit-complete` `denied` (409): TripIt refused the token exchange (the user did not allow access). */
    data object Denied : TripItResult<Nothing>

    /** `tripit-sync` `cooldown` (429): "Sync now" works again after [retryAfterSeconds]. */
    data class Cooldown(val retryAfterSeconds: Int) : TripItResult<Nothing>

    /** `tripit_error` (502): TripIt itself failed. */
    data object TripItDown : TripItResult<Nothing>

    /** `not_configured` (503): the server has no TripIt consumer key yet; the row is hidden. */
    data object NotOffered : TripItResult<Nothing>

    /** This build has no backend keys. */
    data object BackendNotConfigured : TripItResult<Nothing>
    data object Offline : TripItResult<Nothing>

    /** [message] is technical English: log it, never show it. */
    data class Failed(val message: String?) : TripItResult<Nothing>
}

/** What the callback redirect said. */
sealed interface TripItLink {
    /** Legacy redirect (before the login CSRF fix): the server already connected. */
    data object Connected : TripItLink

    /** `heygate://tripit/callback?code=...`: the app finishes with `tripit-complete` as the signed-in user. */
    data class Code(val code: String) : TripItLink {
        override fun toString(): String = "Code(***)" // a live one-time secret: never logged
    }

    data class Failed(val reason: TripItLinkError) : TripItLink
}

enum class TripItLinkError { DENIED, EXPIRED, TRIPIT_ERROR, NOT_CONFIGURED, INTERNAL }

private val TripItJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class StatusDto(
    val connected: Boolean = false,
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
private data class CompleteDto(val code: String)

@Serializable
private data class ConnectDto(@SerialName("authorize_url") val authorizeUrl: String? = null)

object TripItParser {
    fun status(element: JsonElement): TripItStatus {
        if (element is JsonNull) return TripItStatus(connected = false, lastSyncAt = null, lastError = null)
        val dto = TripItJson.decodeFromJsonElement(StatusDto.serializer(), element)
        return TripItStatus(
            connected = dto.connected,
            lastSyncAt = dto.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            lastError = when (dto.lastError) {
                "tripit_revoked" -> TripItLastError.REVOKED
                "tripit_error" -> TripItLastError.TRIPIT_ERROR
                else -> null
            },
        )
    }

    /** The authorize URL, only when it is an https URL on tripit.com (never open anything else). */
    fun authorizeUrl(body: String): String {
        val url = TripItJson.decodeFromString(ConnectDto.serializer(), body).authorizeUrl
            ?: throw IllegalArgumentException("no authorize_url")
        require(isTripItAuthorizeUrl(url)) { "unexpected authorize_url host" }
        return url
    }

    fun syncSummary(body: String): TripItSyncSummary = TripItJson.decodeFromString(TripItSyncSummary.serializer(), body)

    fun isTripItAuthorizeUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && (host == "tripit.com" || host.endsWith(".tripit.com"))
    }

    /**
     * Maps an error answer's `error` code. [message] is the body's `message`; for `cooldown` it is
     * `retry_after_s=<n>` (the server also sends `retry_after_s`, which the shared function error DTO drops).
     */
    fun <T> refused(code: String?, message: String? = null): TripItResult<T> = when (code) {
        "pro_required" -> TripItResult.ProRequired
        "not_your_link" -> TripItResult.NotYourLink
        "expired" -> TripItResult.Expired
        "denied" -> TripItResult.Denied
        "cooldown" -> TripItResult.Cooldown(retryAfterSeconds(message))
        "not_connected" -> TripItResult.NotConnected
        "tripit_revoked" -> TripItResult.Revoked
        "tripit_error" -> TripItResult.TripItDown
        "not_configured" -> TripItResult.NotOffered
        else -> TripItResult.Failed("tripit: ${code ?: "http error"}")
    }

    /** Seconds from a cooldown answer's `message` ("retry_after_s=42"), clamped to 1..60; 60 when unreadable. */
    fun retryAfterSeconds(message: String?): Int =
        message?.let { Regex("retry_after_s=(\\d{1,6})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.coerceIn(1, 60) ?: 60

    /** The body of `tripit-complete`: `{"code":"..."}`. */
    fun completeBody(code: String): String =
        TripItJson.encodeToString(CompleteDto.serializer(), CompleteDto(code))

    /**
     * `heygate://tripit/callback?code=...`, `heygate://tripit/connected` or `heygate://tripit/failed?error=...`;
     * null for anything else. A callback without a well-formed code reads as a failed connect.
     */
    fun link(uri: String?): TripItLink? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals(TripItContract.SCHEME, ignoreCase = true)) return null
        if (!parsed.host.equals(TripItContract.HOST, ignoreCase = true)) return null
        return when (parsed.path?.trimEnd('/')) {
            TripItContract.PATH_CONNECTED -> TripItLink.Connected
            TripItContract.PATH_CALLBACK -> queryParam(parsed.rawQuery, "code")
                ?.takeIf { TripItContract.CODE_PATTERN.matches(it) }
                ?.let { TripItLink.Code(it) }
                ?: TripItLink.Failed(TripItLinkError.INTERNAL)
            TripItContract.PATH_FAILED -> TripItLink.Failed(
                when (queryParam(parsed.rawQuery, "error")) {
                    "denied" -> TripItLinkError.DENIED
                    "expired" -> TripItLinkError.EXPIRED
                    "tripit_error" -> TripItLinkError.TRIPIT_ERROR
                    "not_configured" -> TripItLinkError.NOT_CONFIGURED
                    else -> TripItLinkError.INTERNAL
                },
            )
            else -> null
        }
    }

    private fun queryParam(rawQuery: String?, name: String): String? = rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == name }
        ?.getOrNull(1)
        ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
}

/**
 * The latest TripIt redirect the activity received, until the TripIt section consumes it. The app uses the
 * process-wide [TripItDeepLinks], because the redirect can arrive on a cold start before any screen exists;
 * tests use their own instance.
 */
open class TripItLinkStore {
    private val state = MutableStateFlow<TripItLink?>(null)
    val pending: StateFlow<TripItLink?> = state.asStateFlow()

    /** Called by MainActivity with the intent's data; true when it was a TripIt link. */
    fun offer(uri: String?): Boolean {
        val link = TripItParser.link(uri) ?: return false
        state.value = link
        return true
    }

    fun consume(): TripItLink? {
        val link = state.value
        state.value = null
        return link
    }
}

/** The app's one [TripItLinkStore]: MainActivity offers, the NavHost and the TripIt section read. */
object TripItDeepLinks : TripItLinkStore()
