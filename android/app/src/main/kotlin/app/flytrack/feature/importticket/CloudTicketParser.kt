package app.flytrack.feature.importticket

import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.normalizePnr
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendException
import app.flytrack.data.repo.AuthRepository
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CloudParseResult {
    data class Parsed(val ticket: ParsedTicket) : CloudParseResult
    data object RateLimited : CloudParseResult
    data object NotConfigured : CloudParseResult
    data object Offline : CloudParseResult
    data object Failed : CloudParseResult
}

@Serializable
data class ParseTicketRequest(val text: String)

@Serializable
data class ParseTicketResponse(
    val pnr: String? = null,
    val flights: List<ParseTicketFlightDto> = emptyList(),
)

@Serializable
data class ParseTicketFlightDto(
    val flight: String? = null,
    val date: String? = null,
    val from: String? = null,
    val to: String? = null,
)

/** Sends extracted ticket text (never the file) to the `parse-ticket` Edge Function. */
@Singleton
open class CloudTicketParser @Inject constructor(
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val json: Json,
) {
    open suspend fun parse(text: String): CloudParseResult {
        val clipped = text.take(MAX_TICKET_CHARS)
        if (clipped.isBlank()) return CloudParseResult.Failed
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success -> Unit
            AuthResult.NotConfigured -> return CloudParseResult.NotConfigured
            AuthResult.Offline -> return CloudParseResult.Offline
            AuthResult.GoogleAccountInUse, is AuthResult.Failed -> return CloudParseResult.Failed
        }
        return try {
            val body = api.invokeFunction("parse-ticket", json.encodeToString(ParseTicketRequest(clipped)))
            CloudParseResult.Parsed(json.decodeFromString<ParseTicketResponse>(body).toTicket())
        } catch (e: BackendException) {
            when {
                e is BackendException.NotConfigured -> CloudParseResult.NotConfigured
                e is BackendException.Network -> CloudParseResult.Offline
                e is BackendException.Http && (e.code == "rate_limited" || e.status == 429) -> CloudParseResult.RateLimited
                e is BackendException.Http && e.code == "not_configured" -> CloudParseResult.NotConfigured
                else -> CloudParseResult.Failed
            }
        }
    }
}

/** Drops anything the model returned that is not a real flight number, date, airport or PNR. */
fun ParseTicketResponse.toTicket(): ParsedTicket {
    val flights = flights.mapNotNull { dto ->
        val flight = dto.flight?.let { parseFlightNumber(it) } ?: return@mapNotNull null
        val date = dto.date?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }
        ParsedFlight(
            flight = flight,
            date = date,
            fromIata = dto.from?.trim()?.uppercase()?.takeIf { it.length == 3 && it.all { ch -> ch.isLetter() } },
            toIata = dto.to?.trim()?.uppercase()?.takeIf { it.length == 3 && it.all { ch -> ch.isLetter() } },
        )
    }
    val pnr = normalizePnr(pnr)?.takeIf { it.length == 6 }
    return ParsedTicket(flights, pnr)
}
