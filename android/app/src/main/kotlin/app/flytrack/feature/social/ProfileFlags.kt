package app.flytrack.feature.social

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.di.IoDispatcher
import app.flytrack.feature.friends.RemoteResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The user-editable P3 columns of `profiles` (migration 28, column grants). */
@Serializable
data class ProfileFlags(
    /** B12: mutes check-in, boarding and when-to-leave alerts, hides upsells, shows block times. */
    @SerialName("crew_mode") val crewMode: Boolean = false,
    /** B10: accepted friends may compare Passports with this user. */
    @SerialName("share_passport_with_friends") val sharePassportWithFriends: Boolean = true,
)

/** `profiles` reads and PATCHes of the P3 flags. Throws [BackendException] only. */
interface ProfileFlagsApi {
    /** The caller's flags (RLS: own row), or null when the row is not readable. */
    suspend fun flags(userId: String): ProfileFlags?

    /** PATCH of the given columns; the row after the update, or null when nothing matched. */
    suspend fun update(userId: String, patch: JsonObject): ProfileFlags?
}

class SupabaseProfileFlagsApi @Inject constructor(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : ProfileFlagsApi {

    override suspend fun flags(userId: String): ProfileFlags? = call { client ->
        val result = client.postgrest.from(PROFILES).select(Columns.list(COLUMNS)) { filter { eq("id", userId) } }
        json.decodeFromString(ListSerializer(ProfileFlags.serializer()), result.data).firstOrNull()
    }

    override suspend fun update(userId: String, patch: JsonObject): ProfileFlags? = call { client ->
        val result = client.postgrest.from(PROFILES).update(patch) {
            filter { eq("id", userId) }
            select(Columns.list(COLUMNS))
        }
        json.decodeFromString(ListSerializer(ProfileFlags.serializer()), result.data).firstOrNull()
    }

    /** Same error conversion as the other Supabase doors. */
    private suspend fun <T> call(block: suspend (SupabaseClient) -> T): T {
        val client = provider.client ?: throw BackendException.NotConfigured()
        return try {
            block(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            throw e
        } catch (e: PostgrestRestException) {
            throw BackendException.Http(status = e.statusCode, code = e.code, message = e.error, hint = e.hint)
        } catch (e: RestException) {
            throw BackendException.Http(status = e.statusCode, code = null, message = e.error)
        } catch (e: SerializationException) {
            throw BackendException.Malformed(e)
        } catch (e: IllegalArgumentException) {
            throw BackendException.Malformed(e)
        } catch (e: IOException) {
            throw BackendException.Network(e)
        }
    }

    private companion object {
        const val PROFILES = "profiles"
        const val CREW_MODE = "crew_mode"
        const val SHARE_PASSPORT = "share_passport_with_friends"
        val COLUMNS = listOf(CREW_MODE, SHARE_PASSPORT)
    }
}

/**
 * Crew mode (B12) and Passport sharing (B10): the two P3 profile switches. Commands never throw.
 * [crewMode] reads the device cache, so screens and workers know it offline and at once.
 */
interface ProfileFlagsRepository {
    /** True while crew mode is on for the signed-in user. False until the first [refresh]. */
    val crewMode: Flow<Boolean>

    /** Reads both flags from the server and caches crew mode. */
    suspend fun refresh(): RemoteResult<ProfileFlags>
    suspend fun setCrewMode(enabled: Boolean): RemoteResult<ProfileFlags>
    suspend fun setSharePassport(enabled: Boolean): RemoteResult<ProfileFlags>
}

@Singleton
class SupabaseProfileFlagsRepository @Inject constructor(
    private val api: ProfileFlagsApi,
    private val auth: AuthRepository,
    private val store: SocialStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProfileFlagsRepository {

    override val crewMode: Flow<Boolean> = combine(store.crewMode, auth.session) { flag, session ->
        val user = (session as? SessionState.SignedIn)?.userId
        flag != null && user != null && flag.userId == user && flag.enabled
    }.distinctUntilChanged()

    override suspend fun refresh(): RemoteResult<ProfileFlags> = call { userId ->
        api.flags(userId) ?: throw BackendException.Http(404, null, "profile not readable")
    }

    override suspend fun setCrewMode(enabled: Boolean): RemoteResult<ProfileFlags> = call { userId ->
        api.update(userId, buildJsonObject { put(CREW_MODE, enabled) }) ?: throw BackendException.Http(404, null, "profile not found")
    }

    override suspend fun setSharePassport(enabled: Boolean): RemoteResult<ProfileFlags> = call { userId ->
        api.update(userId, buildJsonObject { put(SHARE_PASSPORT, enabled) }) ?: throw BackendException.Http(404, null, "profile not found")
    }

    /** Signs in, runs [block], caches crew mode from its answer, and maps every error. */
    private suspend fun call(block: suspend (userId: String) -> ProfileFlags): RemoteResult<ProfileFlags> = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext RemoteResult.NotConfigured
            AuthResult.Offline -> return@withContext RemoteResult.Offline
            is AuthResult.Failed -> return@withContext RemoteResult.Failed(signedIn.message)
        }
        val userId = auth.currentUserId ?: return@withContext RemoteResult.Failed("no user id after sign-in")
        try {
            val flags = block(userId)
            store.setCrewMode(CrewModeFlag(userId, flags.crewMode))
            RemoteResult.Ok(flags)
        } catch (e: BackendException) {
            when (e) {
                is BackendException.NotConfigured -> RemoteResult.NotConfigured
                is BackendException.Network -> RemoteResult.Offline
                is BackendException.Malformed -> RemoteResult.Failed(e.message)
                is BackendException.Http -> RemoteResult.Refused(e.code, e.message)
            }
        }
    }

    private companion object {
        const val CREW_MODE = "crew_mode"
        const val SHARE_PASSPORT = "share_passport_with_friends"
    }
}
