package app.flytrack

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.util.Log
import app.flytrack.data.repo.SettingsStore
import app.flytrack.di.ApplicationScope
import app.flytrack.feature.gmailimport.GMAIL_LOG_TAG
import app.flytrack.feature.gmailimport.GmailConnection
import app.flytrack.feature.gmailimport.gmailLeftoversNeedWipe
import app.flytrack.push.PushInitializer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FlyTrackApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var pushInitializer: PushInitializer

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var gmailConnection: dagger.Lazy<GmailConnection>

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    // WorkManager's default initializer is removed in the manifest so @HiltWorker classes get injected.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        pushInitializer.onAppStart()
        forgetGmailWhenSwitchedOff()
    }

    /**
     * A build with Gmail import switched off forgets what a build with it on left here (for example a release
     * installed over a debug build): account, suggestions, the background check. No network, so no revoke.
     */
    private fun forgetGmailWhenSwitchedOff() {
        appScope.launch {
            try {
                if (gmailLeftoversNeedWipe(settings.gmailAccountEmail.first())) gmailConnection.get().clearLocal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(GMAIL_LOG_TAG, "gmail wipe failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
