package app.flytrack.feature.gmailimport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// ponytail: no-op stand-ins until Task 7 binds AndroidGmailNotifier and WorkManagerGmailScheduler; Task 7 deletes this file.
@Module
@InstallIn(SingletonComponent::class)
object GmailInterimBindings {
    @Provides fun notifier(): GmailNotifier = object : GmailNotifier {
        override fun notifyNew(suggestions: List<GmailSuggestion>) = Unit
        override fun cancel(ids: List<String>) = Unit
        override fun cancelAll() = Unit
    }

    @Provides fun work(): GmailWorkScheduler = object : GmailWorkScheduler {
        override fun schedule() = Unit
        override fun cancel() = Unit
    }
}
