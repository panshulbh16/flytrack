package app.flytrack.feature.gmailimport

import app.flytrack.data.repo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private val REVOKE_TIMEOUT = 10.seconds

/** Starts and stops the periodic background check (WorkManager in production). */
interface GmailWorkScheduler {
    fun schedule()
    fun cancel()
}

/** Connecting and disconnecting Gmail, and forgetting everything Gmail-derived on the phone. */
class GmailConnection @Inject constructor(
    private val auth: GmailAuth,
    private val api: GmailApi,
    private val store: GmailSuggestionStore,
    private val settings: SettingsStore,
    private val notifier: GmailNotifier,
    private val work: GmailWorkScheduler,
) {
    /**
     * Finishes Connect Gmail with the token from the account picker: stores the chosen address
     * (users.getProfile), clears "needs reconnect" and starts the background check when Auto-check
     * is on. Throws [GmailApiException] when the profile cannot be read; nothing is saved then.
     */
    suspend fun completeConnect(token: String) {
        val email = api.profile(token).emailAddress
        if (settings.gmailAccountEmail.first() != email) {
            // Another mailbox: its first scan looks back 180 days, not from the old one's last check.
            settings.setGmailLastCheckEpochSeconds(null)
        }
        settings.setGmailAccountEmail(email)
        settings.setGmailNeedsReconnect(false)
        if (settings.gmailAutoCheck.first()) work.schedule()
    }

    /**
     * Disconnect Gmail (Settings) and HeyGate sign-out: [clearLocal] first, so the wipe is immediate and leaving
     * Settings cannot cancel it, then a best-effort revoke of Google's grant, bounded so sign-out (which holds the
     * auth lock) never waits long on the network.
     */
    suspend fun disconnect() {
        val email = settings.gmailAccountEmail.first()
        clearLocal()
        email?.let {
            withTimeoutOrNull(REVOKE_TIMEOUT) {
                try {
                    auth.revoke(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Offline or already revoked: everything here is forgotten already. The user can remove
                    // the grant at myaccount.google.com.
                }
            }
        }
    }

    /** Forgets the account, suggestions, seen ids and Gmail settings, stops the check and removes Gmail notifications. No network. */
    suspend fun clearLocal() {
        // First, so a running scan's account check fails before the store is wiped, not after (GmailScanner.scan).
        settings.setGmailAccountEmail(null)
        work.cancel()
        notifier.cancelAll()
        store.clear()
        settings.setGmailAutoCheck(true)
        settings.setGmailLastCheckEpochSeconds(null)
        settings.setGmailNeedsReconnect(false)
        settings.setGmailLastAppOpenCheckEpochSeconds(null)
    }
}
