package app.flytrack.feature.passport

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.TravelApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import javax.inject.Inject

sealed interface PassportResult {
    data class Loaded(val passport: Passport) : PassportResult
    data object Offline : PassportResult
    data object NotConfigured : PassportResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : PassportResult
}

interface PassportRepository {
    /**
     * `my_passport(p_year)`. [year] null = all time for Pro, last 12 months for free users; a year = that
     * calendar year (always free for the current one). Never throws.
     */
    suspend fun passport(year: Int?, fallbackYear: Int): PassportResult
}

class SupabasePassportRepository @Inject constructor(
    private val api: TravelApi,
    private val auth: AuthRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PassportRepository {

    override suspend fun passport(year: Int?, fallbackYear: Int): PassportResult = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext PassportResult.NotConfigured
            AuthResult.Offline -> return@withContext PassportResult.Offline
            is AuthResult.Failed -> return@withContext PassportResult.Failed(signedIn.message)
        }
        try {
            PassportResult.Loaded(parsePassport(api.myPassport(year), year ?: fallbackYear))
        } catch (e: BackendException) {
            when (e) {
                is BackendException.NotConfigured -> PassportResult.NotConfigured
                is BackendException.Network -> PassportResult.Offline
                is BackendException.Malformed -> PassportResult.Failed(e.message)
                is BackendException.Http -> PassportResult.Failed(e.message)
            }
        } catch (e: SerializationException) {
            PassportResult.Failed(e.message)
        }
    }
}
