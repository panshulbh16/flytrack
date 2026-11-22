package app.flytrack.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.flytrack.data.repo.FlightRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Writes one FCM sync payload into Room. Expedited so the process is kept alive for the write
 * after the messaging service returns.
 */
@HiltWorker
class FlightSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val flights: FlightRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flightId = inputData.getString(KEY_FLIGHT_ID) ?: return Result.failure()
        val json = inputData.getString(KEY_FLIGHT)
        val outcome = syncWorkOutcome(
            result = flights.applyServerSync(flightId, json),
            attempt = runAttemptCount + 1,
        )
        return when (outcome) {
            SyncWorkOutcome.SUCCESS -> Result.success()
            SyncWorkOutcome.RETRY -> Result.retry()
            SyncWorkOutcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_FLIGHT_ID = "flight_id"
        const val KEY_FLIGHT = "flight"
    }
}
