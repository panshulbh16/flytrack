package app.flytrack.feature.gmailimport

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.flytrack.data.repo.SettingsStore
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The automatic Gmail check: lifecycle cleanup, one scan, and a notification for each new find.
 * Offline, rate-limited and partial scans leave lastCheck to the scanner and wait for the next period.
 */
@HiltWorker
class GmailCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: GmailScanner,
    private val settings: SettingsStore,
    private val notifier: GmailNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val enabled = gmailAutoCheckEnabled(
            accountEmail = settings.gmailAccountEmail.first(),
            autoCheck = settings.gmailAutoCheck.first(),
            needsReconnect = settings.gmailNeedsReconnect.first(),
        )
        if (!enabled) return Result.success()
        scanner.cleanup(notifier)
        notifier.notifyNew(scanner.scan().newSuggestions)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "gmail-check"
    }
}

/** Runs [GmailCheckWorker] every 6 hours when there is a network. Enqueued on connect, cancelled on disconnect or when Auto-check is off. */
@Singleton
class WorkManagerGmailScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : GmailWorkScheduler {

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<GmailCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE keeps the current period when the work is already enqueued (reconnect, Auto-check on again).
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(GmailCheckWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(GmailCheckWorker.UNIQUE_NAME)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GmailBackgroundModule {
    @Binds
    @Singleton
    abstract fun bindNotifier(impl: AndroidGmailNotifier): GmailNotifier

    @Binds
    @Singleton
    abstract fun bindWorkScheduler(impl: WorkManagerGmailScheduler): GmailWorkScheduler
}
