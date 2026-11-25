package app.flytrack.feature.gmailimport

import android.util.Log
import app.flytrack.BuildConfig
import app.flytrack.data.repo.SettingsStore
import app.flytrack.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

private const val APP_OPEN_THROTTLE_SECONDS = 3_600L

internal const val GMAIL_LOG_TAG = "FlyTrackGmail"

/**
 * The automatic check (worker and app open) runs only in a build with the release switch on, for a connected
 * account with Auto-check on and access not lost.
 */
fun gmailAutoCheckEnabled(
    accountEmail: String?,
    autoCheck: Boolean,
    needsReconnect: Boolean,
    importEnabled: Boolean = BuildConfig.GMAIL_IMPORT_ENABLED,
): Boolean = importEnabled && accountEmail != null && autoCheck && !needsReconnect

/** A build with the release switch off forgets the Gmail state a build with it on left on the phone. */
fun gmailLeftoversNeedWipe(accountEmail: String?, importEnabled: Boolean = BuildConfig.GMAIL_IMPORT_ENABLED): Boolean =
    !importEnabled && accountEmail != null

/** App-open check: at most once an hour. A last-check time in the future (clock set back) does not block it. */
fun gmailAppOpenCheckDue(
    accountEmail: String?,
    autoCheck: Boolean,
    needsReconnect: Boolean,
    lastAppOpenCheckEpochSeconds: Long?,
    nowEpochSeconds: Long,
    importEnabled: Boolean = BuildConfig.GMAIL_IMPORT_ENABLED,
): Boolean {
    if (!gmailAutoCheckEnabled(accountEmail, autoCheck, needsReconnect, importEnabled)) return false
    val last = lastAppOpenCheckEpochSeconds ?: return true
    return nowEpochSeconds < last || nowEpochSeconds - last >= APP_OPEN_THROTTLE_SECONDS
}

/** "DEL→BOM" for the notification text, or null when either airport is unknown. */
fun GmailSuggestion.notificationRoute(): String? =
    if (fromIata != null && toIata != null) "$fromIata→$toIata" else null

/**
 * Cleanup plus a scan when the app comes to the foreground, throttled to once an hour. It posts no
 * notifications: the app is open and the Found in Gmail card shows new finds. Runs in the
 * application scope so a rotation does not cut the scan short.
 */
@Singleton
class GmailAppOpenCheck @Inject constructor(
    private val scanner: GmailScanner,
    private val settings: SettingsStore,
    private val notifier: GmailNotifier,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var running: Job? = null

    /** Called from MainActivity.onStart on the main thread. */
    fun onForeground() {
        if (running?.isActive == true) return
        running = scope.launch {
            try {
                val now = clock.now().epochSeconds
                val due = gmailAppOpenCheckDue(
                    accountEmail = settings.gmailAccountEmail.first(),
                    autoCheck = settings.gmailAutoCheck.first(),
                    needsReconnect = settings.gmailNeedsReconnect.first(),
                    lastAppOpenCheckEpochSeconds = settings.gmailLastAppOpenCheckEpochSeconds.first(),
                    nowEpochSeconds = now,
                )
                if (!due) return@launch
                settings.setGmailLastAppOpenCheckEpochSeconds(now)
                scanner.cleanup(notifier)
                scanner.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A disk or Play services error skips this check instead of crashing the app. Class name only:
                // a message could carry mail text.
                Log.w(GMAIL_LOG_TAG, "app-open check failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
