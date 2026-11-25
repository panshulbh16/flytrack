package app.flytrack.feature.gmailimport

import app.flytrack.data.remote.FlyTrackJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Runs KtorGmailApi against ktor-client-mock: exact URLs, headers, decoding and error mapping. */
class KtorGmailApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorGmailApi {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return KtorGmailApi(HttpClient(engine), FlyTrackJson)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, jsonHeaders)

    private fun googleError(code: Int, reason: String) =
        """{"error":{"code":$code,"message":"denied","errors":[{"message":"denied","domain":"global","reason":"$reason"}],"status":"PERMISSION_DENIED"}}"""

    private suspend fun gmailError(block: suspend () -> Unit): GmailApiException = try {
        block()
        throw AssertionError("expected a GmailApiException")
    } catch (e: GmailApiException) {
        e
    }

    @Test
    fun `list sends query, page size and page token with only the Google bearer token`() = runTest {
        val api = api {
            json("""{"messages":[{"id":"m1","threadId":"t1"},{"id":"m2","threadId":"t2"}],"nextPageToken":"p2","resultSizeEstimate":2}""")
        }
        val query = """subject:(ticket OR "boarding pass") newer_than:180d"""

        val page = api.list("tok", query, pageToken = "p1")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("gmail.googleapis.com", request.url.host)
        assertEquals("/gmail/v1/users/me/messages", request.url.encodedPath)
        assertEquals(query, request.url.parameters["q"])
        assertEquals("100", request.url.parameters["maxResults"])
        assertEquals("p1", request.url.parameters["pageToken"])
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["apikey"])
        assertEquals(listOf("m1", "m2"), page.messages.map { it.id })
        assertEquals("p2", page.nextPageToken)
    }

    @Test
    fun `the first page has no page token and an empty mailbox decodes to no messages`() = runTest {
        val api = api { json("""{"resultSizeEstimate":0}""") }

        val page = api.list("tok", "subject:ticket", pageToken = null)

        assertNull(requests.single().url.parameters["pageToken"])
        assertEquals(emptyList<GmailMessageRef>(), page.messages)
        assertNull(page.nextPageToken)
    }

    @Test
    fun `message fetches the full format by id`() = runTest {
        val api = api {
            json("""{"id":"m1","threadId":"t1","payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"Your e-ticket"}],"body":{"size":5,"data":"SGVsbG8"}}}""")
        }

        val message = api.message("tok", "m1")

        val request = requests.single()
        assertEquals("/gmail/v1/users/me/messages/m1", request.url.encodedPath)
        assertEquals("full", request.url.parameters["format"])
        assertEquals("m1", message.id)
        assertEquals("Your e-ticket", message.payload?.headers?.single()?.value)
    }

    @Test
    fun `attachment returns the base64url-decoded bytes`() = runTest {
        val api = api { json("""{"size":9,"data":"SGVsbG8gUERG"}""") }

        val bytes = api.attachment("tok", "m1", "a1")

        assertEquals("/gmail/v1/users/me/messages/m1/attachments/a1", requests.single().url.encodedPath)
        assertArrayEquals("Hello PDF".toByteArray(), bytes)
    }

    @Test
    fun `an attachment without data is Other`() = runTest {
        val api = api { json("""{"size":0}""") }
        assertEquals(GmailFailure.Other, gmailError { api.attachment("tok", "m1", "a1") }.failure)
    }

    @Test
    fun `profile reads the connected address`() = runTest {
        val api = api { json("""{"emailAddress":"traveller@gmail.com","messagesTotal":10,"threadsTotal":8,"historyId":"1"}""") }

        val profile = api.profile("tok")

        assertEquals("/gmail/v1/users/me/profile", requests.single().url.encodedPath)
        assertEquals("traveller@gmail.com", profile.emailAddress)
    }

    @Test
    fun `HTTP errors map to GmailFailure as the spec's error table says`() = runTest {
        var status = HttpStatusCode.OK
        var body = ""
        val api = api { json(body, status) }
        val cases = listOf(
            Triple(401, googleError(401, "authError"), GmailFailure.Unauthorized),
            Triple(403, googleError(403, "insufficientPermissions"), GmailFailure.AccessLost),
            Triple(403, googleError(403, "forbidden"), GmailFailure.AccessLost),
            Triple(403, googleError(403, "rateLimitExceeded"), GmailFailure.RateLimited),
            Triple(403, googleError(403, "userRateLimitExceeded"), GmailFailure.RateLimited),
            Triple(403, googleError(403, "dailyLimitExceeded"), GmailFailure.RateLimited),
            Triple(429, googleError(429, "rateLimitExceeded"), GmailFailure.RateLimited),
            Triple(500, "<html>oops</html>", GmailFailure.RateLimited),
            Triple(503, googleError(503, "backendError"), GmailFailure.RateLimited),
            Triple(403, googleError(403, "domainPolicy"), GmailFailure.Other),
            Triple(403, "not json", GmailFailure.Other),
            Triple(404, googleError(404, "notFound"), GmailFailure.Other),
        )
        for ((code, errorBody, expected) in cases) {
            status = HttpStatusCode.fromValue(code)
            body = errorBody
            assertEquals("HTTP $code $errorBody", expected, gmailError { api.message("tok", "m1") }.failure)
        }
    }

    @Test
    fun `connection failures are Offline`() = runTest {
        val api = api { throw IOException("Unable to resolve host") }
        assertEquals(GmailFailure.Offline, gmailError { api.list("tok", "q", null) }.failure)
    }

    @Test
    fun `an unreadable success body is Other`() = runTest {
        val api = api { json("not json") }
        assertEquals(GmailFailure.Other, gmailError { api.profile("tok") }.failure)
    }

    @Test
    fun `error messages never carry the response body, which can quote email content`() = runTest {
        val api = api { json("""{"error":{"code":404,"message":"Subject: Your e-ticket to BOM"}}""", HttpStatusCode.NotFound) }
        val error = gmailError { api.message("tok", "m1") }
        assertFalse(error.message.orEmpty().contains("e-ticket"))
    }
}
