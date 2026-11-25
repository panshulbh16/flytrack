package app.flytrack.feature.gmailimport

import app.flytrack.data.repo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

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

    /** Disconnect Gmail (Settings) and HeyGate sign-out: revokes Google's grant, then [clearLocal]. */
    suspend fun disconnect() {
        settings.gmailAccountEmail.first()?.let { email ->
            try {
                auth.revoke(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or already revoked: still forget everything here. The user can remove
                // the grant at myaccount.google.com.
            }
        }
        clearLocal()
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
