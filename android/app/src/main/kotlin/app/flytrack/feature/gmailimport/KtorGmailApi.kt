package app.flytrack.feature.gmailimport

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * [GmailApi] on its own Ktor client, separate from SupabaseClientProvider's: Gmail works without
 * backend keys and every request carries only `Authorization: Bearer <Google token>`.
 * Exception messages never include response bodies, because those can quote email content.
 */
class KtorGmailApi(
    private val client: HttpClient,
    private val json: Json,
) : GmailApi {

    override suspend fun profile(token: String): GmailProfile =
        get(token, GmailProfile.serializer(), "profile")

    override suspend fun list(token: String, query: String, pageToken: String?): GmailListResponse =
        get(token, GmailListResponse.serializer(), "messages") {
            parameter("q", query)
            parameter("maxResults", PAGE_SIZE)
            if (pageToken != null) parameter("pageToken", pageToken)
        }

    override suspend fun message(token: String, id: String): GmailMessage =
        get(token, GmailMessage.serializer(), "messages", id) { parameter("format", "full") }

    override suspend fun attachment(token: String, messageId: String, attachmentId: String): ByteArray {
        val attachment = get(token, GmailAttachment.serializer(), "messages", messageId, "attachments", attachmentId)
        val data = attachment.data ?: throw GmailApiException(GmailFailure.Other, "attachment without data")
        return try {
            decodeBase64Url(data)
        } catch (e: IllegalArgumentException) {
            throw GmailApiException(GmailFailure.Other, "attachment is not base64url")
        }
    }

    private suspend fun <T> get(
        token: String,
        serializer: KSerializer<T>,
        vararg path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val body = try {
            val response = client.get(BASE_URL) {
                url.appendPathSegments(*path)
                bearerAuth(token)
                configure()
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw GmailApiException(failureFor(response.status.value, text), "Gmail HTTP ${response.status.value}")
            }
            text
        } catch (e: IOException) {
            throw GmailApiException(GmailFailure.Offline, e.javaClass.simpleName)
        }
        return try {
            json.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw GmailApiException(GmailFailure.Other, "unreadable Gmail response")
        }
    }

    /** The spec's error table. 5xx stops the scan like a rate limit; lastCheck stays unchanged. */
    private fun failureFor(status: Int, body: String): GmailFailure = when {
        status == 401 -> GmailFailure.Unauthorized
        status == 429 || status >= 500 -> GmailFailure.RateLimited
        status == 403 -> {
            val reasons = reasons(body)
            when {
                reasons.any { it in RATE_LIMIT_REASONS } -> GmailFailure.RateLimited
                reasons.any { it in ACCESS_LOST_REASONS } -> GmailFailure.AccessLost
                else -> GmailFailure.Other
            }
        }
        else -> GmailFailure.Other
    }

    /** `error.errors[].reason` from Google's error body; empty when the body is not that shape. */
    private fun reasons(body: String): List<String> = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("errors")?.jsonArray
            ?.mapNotNull { it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val PAGE_SIZE = 100
        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded")
        val ACCESS_LOST_REASONS = setOf("insufficientPermissions", "forbidden")
    }
}
