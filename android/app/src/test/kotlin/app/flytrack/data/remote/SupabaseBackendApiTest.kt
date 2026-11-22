package app.flytrack.data.remote

import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.dto.UserFlightInsertDto
import app.flytrack.testing.Fixtures
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Runs the real supabase-kt client (Postgrest + Functions) against ktor-client-mock, so the exact
 * URLs, query strings, headers and bodies the app sends are pinned down without a live backend.
 */
class SupabaseBackendApiTest {
    private data class Recorded(val method: HttpMethod, val path: String, val query: String, val body: String, val headers: Map<String, String>)

    private val requests = mutableListOf<Recorded>()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(
        config: BackendConfig = BackendConfig("https://demo.supabase.co", "anon-key", ""),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SupabaseBackendApi {
        val engine = MockEngine { request ->
            requests += Recorded(
                method = request.method,
                path = request.url.encodedPath,
                query = request.url.encodedQuery,
                body = request.body.toByteArray().decodeToString(),
                headers = request.headers.entries().associate { it.key to it.value.joinToString(",") } +
                    (request.body.contentType?.let { mapOf(HttpHeaders.ContentType to it.toString()) } ?: emptyMap()),
            )
            handler(request)
        }
        val provider = SupabaseClientProvider(config, FlyTrackJson, engineFactory = { engine }, installAuth = false)
        return SupabaseBackendApi(provider, FlyTrackJson)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, jsonHeaders)

    private suspend fun httpError(block: suspend () -> Unit): BackendException.Http = try {
        block()
        fail("expected a BackendException.Http")
        throw IllegalStateException()
    } catch (e: BackendException.Http) {
        e
    }

    // ---- search-flight ----------------------------------------------------------------------------

    @Test
    fun `searchFlight posts flight and date to the Edge Function and decodes the legs`() = runTest {
        val api = api { json(Fixtures.read("search_response.json")) }

        val legs = api.searchFlight("6E 2341", "2026-11-25")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/functions/v1/search-flight", request.path)
        assertEquals("""{"flight":"6E 2341","date":"2026-11-25"}""", request.body)
        assertTrue(request.headers.getValue(HttpHeaders.ContentType).startsWith("application/json"))
        assertEquals("Bearer anon-key", request.headers[HttpHeaders.Authorization])
        assertEquals("anon-key", request.headers["apikey"])
        assertEquals(listOf("2341", "2032"), legs.map { it.flightNumber })
        assertEquals("Asia/Kolkata", legs[0].depTz)
    }

    @Test
    fun `searchFlight errors carry the function's code and message`() = runTest {
        var status = HttpStatusCode.NotFound
        var fixture = "search_error_not_found.json"
        val api = api { json(Fixtures.read(fixture), status) }

        val notFound = httpError { api.searchFlight("6E 9999", "2026-11-25") }
        assertEquals(404, notFound.status)
        assertEquals("not_found", notFound.code)
        assertEquals("No flight 6E 9999 on 2026-11-25", notFound.message)

        status = HttpStatusCode.TooManyRequests
        fixture = "search_error_rate_limited.json"
        assertEquals("rate_limited", httpError { api.searchFlight("6E 1", "2026-11-25") }.code)

        status = HttpStatusCode.BadGateway
        fixture = "search_error_provider.json"
        assertEquals("provider_error", httpError { api.searchFlight("6E 1", "2026-11-25") }.code)
    }

    @Test
    fun `an error that is not the function's JSON keeps only the status`() = runTest {
        val api = api { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) }
        val error = httpError { api.searchFlight("6E 1", "2026-11-25") }
        assertEquals(502, error.status)
        assertNull(error.code)
    }

    @Test
    fun `invokeFunction is the generic door to any Edge Function`() = runTest {
        var status = HttpStatusCode.OK
        var answer = """{"flight":"6E 2341","date":"2026-11-25","pnr":"ABC123"}"""
        val api = api { json(answer, status) }

        val text = api.invokeFunction("parse-ticket", """{"text":"PNR ABC123"}""")

        assertEquals(answer, text)
        assertEquals("/functions/v1/parse-ticket", requests.single().path)
        assertEquals("""{"text":"PNR ABC123"}""", requests.single().body)
        assertTrue(requests.single().headers.getValue(HttpHeaders.ContentType).startsWith("application/json"))

        status = HttpStatusCode.Forbidden
        answer = """{"error":"consent_required","message":"Cloud parsing is opt-in"}"""
        val error = httpError { api.invokeFunction("parse-ticket", "{}") }
        assertEquals(403, error.status)
        assertEquals("consent_required", error.code)
        assertEquals("Cloud parsing is opt-in", error.message)
    }

    @Test
    fun `a search answer that is not the expected JSON becomes Malformed`() = runTest {
        val api = api { json("""{"flights":"none"}""") }
        try {
            api.searchFlight("6E 1", "2026-11-25")
            fail("expected Malformed")
        } catch (e: BackendException.Malformed) {
            assertTrue(e.cause != null)
        }
    }

    // ---- PostgREST --------------------------------------------------------------------------------

    @Test
    fun `flightViews selects the view rows by id`() = runTest {
        val api = api { json("[${Fixtures.read("flight_view_pro.json")}]") }

        val rows = api.flightViews(listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/rest/v1/flight_view", request.path)
        assertTrue(request.query, request.query.contains("id=in.%28aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa%2Cbbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb%29") ||
            request.query.contains("id=in.(aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb)"))
        assertEquals("6E", rows.single().carrierIata)
        assertEquals(45, rows.single().toDomain().prediction?.lateMin)
    }

    @Test
    fun `flightViews with no ids makes no request`() = runTest {
        val api = api { json("[]") }
        assertTrue(api.flightViews(emptyList()).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `userFlights reads the table and decodes the rows`() = runTest {
        val api = api { json(Fixtures.read("user_flights.json")) }

        val rows = api.userFlights()

        assertEquals("/rest/v1/user_flights", requests.single().path)
        assertEquals(listOf("ABC123", null), rows.map { it.pnr })
        assertEquals(false, rows[0].notifyPrefs["DEPARTED"])
    }

    @Test
    fun `userFlightByFlightId filters by flight`() = runTest {
        val api = api { json("[]") }
        assertNull(api.userFlightByFlightId("f-1"))
        assertTrue(requests.single().query, requests.single().query.contains("flight_id=eq.f-1"))
    }

    @Test
    fun `insertUserFlight posts one row without nulls and returns the created row`() = runTest {
        val api = api { json(Fixtures.read("user_flights.json"), HttpStatusCode.Created) }

        val row = api.insertUserFlight(UserFlightInsertDto(flightId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", pnr = "ABC123", source = "pdf"))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/rest/v1/user_flights", request.path)
        assertEquals("""[{"flight_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","pnr":"ABC123","source":"pdf"}]""", request.body)
        assertTrue(request.headers.getValue("Prefer"), request.headers.getValue("Prefer").contains("return=representation"))
        assertEquals("dddddddd-dddd-4ddd-8ddd-dddddddddddd", row.id)
    }

    @Test
    fun `the tracking limit raised by the database is recognisable`() = runTest {
        val api = api { json(Fixtures.read("postgrest_error_tracking_limit.json"), HttpStatusCode.BadRequest) }

        val error = httpError { api.insertUserFlight(UserFlightInsertDto(flightId = "f-1")) }

        assertEquals(400, error.status)
        assertEquals("P0001", error.code)
        assertTrue(error.isTrackingLimit)
        assertEquals("Archive an old flight or upgrade to Pro", error.hint)
    }

    @Test
    fun `a duplicate tracking row is recognisable`() = runTest {
        val api = api { json(Fixtures.read("postgrest_error_unique.json"), HttpStatusCode.Conflict) }
        val error = httpError { api.insertUserFlight(UserFlightInsertDto(flightId = "f-1")) }
        assertEquals(409, error.status)
        assertTrue(error.isUniqueViolation)
        assertTrue(!error.isTrackingLimit)
    }

    @Test
    fun `updateUserFlight patches one row, including explicit nulls`() = runTest {
        val api = api { json(Fixtures.read("user_flights.json")) }
        val patch = buildJsonObject {
            put("archived_at", JsonNull)
            put("seat", "2A")
        }

        val row = api.updateUserFlight("dddddddd-dddd-4ddd-8ddd-dddddddddddd", patch)

        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/rest/v1/user_flights", request.path)
        assertTrue(request.query, request.query.contains("id=eq.dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
        assertEquals("""{"archived_at":null,"seat":"2A"}""", request.body)
        assertEquals("ABC123", row?.pnr)
    }

    @Test
    fun `updateUserFlight returns null when the row is gone`() = runTest {
        val api = api { json("[]") }
        assertNull(api.updateUserFlight("gone", buildJsonObject { put("note", "x") }))
    }

    @Test
    fun `deleteUserFlight deletes by id`() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }
        api.deleteUserFlight("uf-1")
        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/rest/v1/user_flights", request.path)
        assertTrue(request.query, request.query.contains("id=eq.uf-1"))
    }

    @Test
    fun `airports and profile`() = runTest {
        var body = Fixtures.read("airports.json")
        val api = api { json(body) }

        assertEquals(listOf("BOM", "DEL"), api.airports(listOf("DEL", "BOM")).map { it.iata })
        assertEquals("/rest/v1/airports", requests.last().path)
        assertTrue(requests.last().query, requests.last().query.contains("iata=in."))

        body = Fixtures.read("profile.json")
        assertEquals("pro", api.profile("11111111-1111-4111-8111-111111111111")?.plan)
        assertEquals("/rest/v1/profiles", requests.last().path)
        assertTrue(requests.last().query.contains("id=eq.11111111-1111-4111-8111-111111111111"))
    }

    @Test
    fun `registerDevice calls the RPC with the SQL parameter names`() = runTest {
        val api = api { json(Fixtures.read("register_device_result.json")) }

        val deviceId = api.registerDevice(RegisterDeviceParams("fcm-token-0123456789abcdefghij", "0.1.0", "16", "en-IN", "Asia/Kolkata"))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/rest/v1/rpc/register_device", request.path)
        assertEquals(
            """{"p_token":"fcm-token-0123456789abcdefghij","p_app_version":"0.1.0","p_os_version":"16","p_locale":"en-IN","p_timezone":"Asia/Kolkata"}""",
            request.body,
        )
        assertEquals("5dd51af7-51c8-4cc5-a714-0c969a49858b", deviceId)
    }

    @Test
    fun `unregisterDevice calls its RPC`() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }
        api.unregisterDevice("tok")
        assertEquals("/rest/v1/rpc/unregister_device", requests.single().path)
        assertEquals("""{"p_token":"tok"}""", requests.single().body)
    }

    @Test
    fun `setDeviceNotificationsEnabled patches this phone's device row`() = runTest {
        var answer = """[{"id":"d1","notifications_enabled":false}]"""
        val api = api { json(answer) }

        assertTrue(api.setDeviceNotificationsEnabled("tok-1", enabled = false))

        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/rest/v1/devices", request.path)
        assertTrue(request.query, request.query.contains("fcm_token=eq.tok-1"))
        assertEquals("""{"notifications_enabled":false}""", request.body)

        answer = "[]"
        assertTrue("no row for an unknown token", !api.setDeviceNotificationsEnabled("other", enabled = true))
    }

    // ---- failures ---------------------------------------------------------------------------------

    @Test
    fun `connection failures become Network`() = runTest {
        val api = api { throw IOException("Unable to resolve host") }
        try {
            api.userFlights()
            fail("expected Network")
        } catch (e: BackendException.Network) {
            assertTrue(e.cause is IOException)
        }
    }

    @Test
    fun `an unreadable success body becomes Malformed`() = runTest {
        val api = api { json("""{"unexpected":"shape"}""") }
        try {
            api.userFlights()
            fail("expected Malformed")
        } catch (e: BackendException.Malformed) {
            assertTrue(e.cause != null)
        }
    }

    @Test
    fun `without backend keys every call fails with NotConfigured and no client is built`() = runTest {
        val api = api(BackendConfig("", "", "")) { json("[]") }
        try {
            api.userFlights()
            fail("expected NotConfigured")
        } catch (e: BackendException.NotConfigured) {
            assertTrue(requests.isEmpty())
        }
    }
}
